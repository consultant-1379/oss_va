import groovy.json.JsonSlurper
@Library('ci-cn-pipeline-lib') _
@Library('ci-va-pipeline-lib') va
env.bob = new BobCommand()
    .needDockerSocket(true)
    .toString()

pipeline {
    agent {
        node {
            label 'VA'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 12, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    environment {
        XRAY_SELI = credentials('osscnciArtifactoryAccessTokenSELI')
        XRAY_SERO = credentials('osscnciArtifactoryAccessTokenSERO')
        PDUOSSVA_ARM_TOKEN = credentials('pduossvaArtifactoryAccessTokenSELI')
    }
    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    currentBuild.displayName = "#${env.BUILD_NUMBER}-${env.PRODUCT_SET}"
                }
            }
        }
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Init') {
            steps {
                sh """
                    set +x
                    mkdir -p .bob
                """
                script {
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    }
                    stash includes: "*", name: "ruleset2.0.yaml", allowEmpty: true

                    IMAGES = new ArrayList<String>();
                    IMAGES_KUBE = new ArrayList<String>();
                    IMAGES_KUBE_REPORT = new ArrayList<String>();
                    IMAGES_KUBE_FAIL = [:]
                    IMAGES_KUBESEC_REPORT = new ArrayList<String>();
                    IMAGES_KUBESEC_FAIL = [:]

                    xray_to_scan1 = "xray:\r\n  name: cENM\r\n  version: " + PRODUCT_SET + "\r\n  paths:"
                    xray_skip_scan = "Images"
                    anchore_missing_report = "Images"
                    trivy_missing_report = "Images"
                    kubeaudit_missing_report = "Images"
                    kubesec_missing_report = "Images"
                    ciscat_missing_report = "Images"

                    def csarContentsUrl = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-e2e-ci-generic-local/csar-contents/csar-metadata-artifacts-${PRODUCT_SET}.json"
                    def csarContentsObjectRaw = ["curl", "-H", "accept: application/json", "--url", "${csarContentsUrl}"].execute().text
                    def csarContentsObjectList = new JsonSlurper().parseText(csarContentsObjectRaw)

                    csarContentsObjectList.each {
                        if (it.toString().contains("dependent_docker_images=")) {
                            it."dependent_docker_images".each {
                                image = it.artifactory + it.name + ":" + it.version
                                image = image.replace('\'', '')
                                IMAGES.add(image)
                            }
                        }
                    }
                    csarContentsObjectList = null
                    csarContentsObjectRaw = null
                }
            }
        }
        stage('Fetch Reports') {
            parallel {
                stage('Fetch XRAY') {
                    steps {
                        script {
                            bobXraySELI = new BobCommand()
                                .envVars([
                                    'XRAY_USR': env.XRAY_SELI_USR,
                                    'XRAY_PSW': env.XRAY_SELI_PSW,
                                ])
                                .needDockerSocket(true)
                                .toString()
                            bobXraySERO = new BobCommand()
                                .envVars([
                                    'XRAY_USR': env.XRAY_SERO_USR,
                                    'XRAY_PSW': env.XRAY_SERO_PSW,
                                ])
                                .needDockerSocket(true)
                                .toString()
                            imagesCombined = new JsonSlurper().parseText("[]")
                            IMAGES.each { IMAGE ->
                                arm_server = IMAGE.tokenize('/')[0]
                                arm_repository = IMAGE.tokenize('/')[1]
                                if (arm_server == 'armdocker.rnd.ericsson.se') {
                                    bobXray = bobXraySELI
                                    if ((arm_repository == 'proj-enm') || (arm_repository == 'proj-common-assets-cd')) {
                                        xrayConfig(IMAGE, arm_server, 'ARM-SELI/' + arm_repository + '-docker-global')
                                    } else {
                                        xrayConfig(IMAGE, arm_server, 'ARM-SELI/docker-v2-global-' + arm_repository + '-xray-local')
                                    }
                                } else if ((arm_server == 'selndocker.mo.sw.ericsson.se') || (arm_server == 'serodocker.sero.gic.ericsson.se')) {
                                    bobXray = bobXraySERO
                                    xrayConfig(IMAGE, arm_server, 'ARM-SERO/' + arm_repository + '-docker-local')
                                } else {
                                    xray_skip_scan = xray_skip_scan + "\r\n " + IMAGE
                                }
                                imagesCombined = images + imagesCombined
                            }
                            xrayReport['images'] = imagesCombined
                            writeJSON file: 'xray_report.json', json: xrayReport, pretty: 2
                            archiveArtifacts artifacts: 'xray_report.json', allowEmptyArchive: true
                            writeFile file: "Xray_Images_Not_Scanned.txt", text: xray_skip_scan
                            archiveArtifacts artifacts: 'Xray_Images_Not_Scanned.txt', allowEmptyArchive: true
                        }
                    }
                }
                stage('Fetch Anchore Grype') {
                    steps {
                        script {
                            sh 'mkdir anchore-reports'
                            IMAGES.each { IMAGE ->
                                def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                                def IMAGE_VERSION = IMAGE.split(':').last()
                                withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
                                    def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/anchore_grype/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-anchore-grype-scan.tar'
                                    def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                                    report_found = !report_check.asBoolean()
                                    if (report_found) {
                                        sh '''
                                            cd anchore-reports
                                            curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/anchore_grype/${IMAGE_NAME}-${IMAGE_VERSION}-anchore-grype-scan.tar
                                            tar -xvf ${IMAGE_NAME}-${IMAGE_VERSION}-anchore-grype-scan.tar
                                        '''
                                    } else {
                                        anchore_missing_report = anchore_missing_report + "\r\n " + IMAGE
                                    }
                                }
                            }
                        }
                        writeFile file: "Anchore_Images_Not_Scanned.txt", text: anchore_missing_report
                        archiveArtifacts artifacts: 'Anchore_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
                stage('Fetch CIS-CAT') {
                    steps {
                        script {
                            sh 'mkdir ciscat-reports'
                            IMAGES.each { IMAGE ->
                                def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                                def IMAGE_VERSION = IMAGE.split(':').last()
                                withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
                                    def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/ciscat/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-ciscat-scan.tar'
                                    def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                                    report_found = !report_check.asBoolean()
                                    if (report_found) {
                                        sh '''
                                            cd ciscat-reports
                                            curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/ciscat/${IMAGE_NAME}-${IMAGE_VERSION}-ciscat-scan.tar
                                            tar -xvf ${IMAGE_NAME}-${IMAGE_VERSION}-ciscat-scan.tar --wildcards --no-anchored '*enriched.json'
                                        '''
                                    } else {
                                        ciscat_missing_report = ciscat_missing_report + "\r\n " + IMAGE
                                    }
                                }
                            }
                        }
                        writeFile file: "CISCAT_Images_Not_Scanned.txt", text: ciscat_missing_report
                        archiveArtifacts artifacts: 'CISCAT_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
                stage('Fetch Trivy') {
                    steps {
                        script {
                            sh 'mkdir trivy-reports'
                            IMAGES.each { IMAGE ->
                                def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                                def IMAGE_VERSION = IMAGE.split(':').last()
                                withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
                                    def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-trivy_scan.json'
                                    def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                                    report_found = !report_check.asBoolean()
                                    if (report_found) {
                                        sh '''
                                            cd trivy-reports
                                            curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/${IMAGE_NAME}-${IMAGE_VERSION}-trivy_scan.json
                                        '''
                                    } else {
                                        trivy_missing_report = trivy_missing_report + "\r\n " + IMAGE
                                    }
                                }
                            }
                            sh '''
                                cd trivy-reports
                                curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/trivy_metadata_${PRODUCT_SET}.properties
                            '''
                        }
                        writeFile file: "Trivy_Images_Not_Scanned.txt", text: trivy_missing_report
                        archiveArtifacts artifacts: 'Trivy_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
                stage('Fetch Kubeaudit') {
                    steps {
                        script {
                            sh 'mkdir kubeaudit-reports'
                            IMAGES.each { IMAGE ->
                                def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                                def IMAGE_VERSION = IMAGE.split(':').last()
                                withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
                                    def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubeaudit/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-kubeaudit-reports.tar'
                                    def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                                    report_found = !report_check.asBoolean()
                                    if (report_found) {
                                        sh '''
                                            cd kubeaudit-reports
                                            curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubeaudit/${IMAGE_NAME}-${IMAGE_VERSION}-kubeaudit-reports.tar
                                            tar --transform "s,^,${IMAGE_NAME}-${IMAGE_VERSION}_," -xvf ${IMAGE_NAME}-${IMAGE_VERSION}-kubeaudit-reports.tar
                                            find . -type f -empty -print -delete
                                            rm *.tar
                                        '''
                                    } else {
                                        kubeaudit_missing_report = kubeaudit_missing_report + "\r\n " + IMAGE
                                    }
                                }
                            }
                        }
                        archiveArtifacts allowEmptyArchive: true, artifacts: "kubeaudit-reports/**"
                        writeFile file: "Kubeaudit_Images_Not_Scanned.txt", text: kubeaudit_missing_report
                        archiveArtifacts artifacts: 'Kubeaudit_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
                stage('Fetch Kubesec') {
                    steps {
                        script {
                            sh 'mkdir kubesec-reports'
                            IMAGES.each { IMAGE ->
                                def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                                def IMAGE_VERSION = IMAGE.split(':').last()
                                withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
                                    def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubesec/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-kubesec-reports.tar'
                                    def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                                    report_found = !report_check.asBoolean()
                                    if (report_found) {
                                        sh '''
                                            cd kubesec-reports
                                            curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubesec/${IMAGE_NAME}-${IMAGE_VERSION}-kubesec-reports.tar
                                            tar --transform "s,^,${IMAGE_NAME}-${IMAGE_VERSION}_," -xvf ${IMAGE_NAME}-${IMAGE_VERSION}-kubesec-reports.tar
                                            rm *.tar
                                        '''
                                    } else {
                                        kubesec_missing_report = kubesec_missing_report + "\r\n " + IMAGE
                                    }
                                }
                            }
                        }
                        archiveArtifacts allowEmptyArchive: true, artifacts: "kubesec-reports/**"
                        writeFile file: "Kubesec_Images_Not_Scanned.txt", text: kubesec_missing_report
                        archiveArtifacts artifacts: 'Kubesec_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
                stage('Fetch ZAP') {
                    steps {
                        script {
                            sh 'mkdir zap-reports'
                            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/zap/' + 'cenm-product-set-' + PRODUCT_SET + '-BaseURL_full.json'
                            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                            report_found = !report_check.asBoolean()
                            if (report_found) {
                                sh '''
                                    cd zap-reports
                                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/zap/cenm-product-set-${PRODUCT_SET}-BaseURL_full.json
                                '''
                            } else {
                                error("Missing ZAP report")
                            }
                        }
                    }
                }
                stage('Fetch NMAP') {
                    steps {
                        script {
                            sh 'mkdir nmap-reports'
                            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/nmap/' + 'cenm-product-set-' + PRODUCT_SET + '-nmap-scan.tar'
                            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                            report_found = !report_check.asBoolean()
                            if (report_found) {
                                sh '''
                                    cd nmap-reports
                                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/nmap/cenm-product-set-${PRODUCT_SET}-nmap-scan.tar
                                    tar -xvf cenm-product-set-${PRODUCT_SET}-nmap-scan.tar
                                    rm -f cenm-product-set-${PRODUCT_SET}-nmap-scan.tar
                                '''
                            } else {
                                error("Missing NMAP report")
                            }
                        }
                    }
                }
                stage('Fetch Tenable') {
                    steps {
                        script {
                            sh 'mkdir tenable-reports'
                            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/tenable/' + 'cenm-product-set-' + PRODUCT_SET + '-tenable.tar'
                            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                            report_found = !report_check.asBoolean()
                            if (report_found) {
                                sh '''
                                    cd tenable-reports
                                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/tenable/cenm-product-set-${PRODUCT_SET}-tenable.tar
                                    tar -xvf cenm-product-set-${PRODUCT_SET}-tenable.tar
                                    rm -f cenm-product-set-${PRODUCT_SET}-tenable.tar
                                    rm -f *.pdf
                                    mv *.csv tenable-report.csv
                                '''
                            } else {
                                error("Missing Tenable report")
                            }
                        }
                    }
                }
                stage('Fetch Defensics') {
                    steps {
                        script {
                            sh 'mkdir -p defensics-reports'
                            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/defensics/' + 'cenm-product-set-' + PRODUCT_SET + '-report.xml'
                            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                            report_found = !report_check.asBoolean()
                            if (report_found) {
                                sh '''
                                    cd defensics-reports
                                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/defensics/cenm-product-set-${PRODUCT_SET}-report.xml
                                    cp cenm-product-set-${PRODUCT_SET}-report.xml defensics-reports.xml
                                '''
                            } else {
                                error("Missing Defensics report")
                            }
                        }
                    }
                }
                stage('Fetch Kubehunter') {
                    steps {
                        script {
                            sh 'mkdir kubehunter-reports'
                            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubehunter/' + 'cenm-product-set-' + PRODUCT_SET + '-reports.tar'
                            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                            report_found = !report_check.asBoolean()
                            if (report_found) {
                                sh '''
                                    cd kubehunter-reports
                                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubehunter/cenm-product-set-${PRODUCT_SET}-reports.tar
                                    tar -xvf cenm-product-set-${PRODUCT_SET}-reports.tar
                                    rm -f cenm-product-set-${PRODUCT_SET}-reports.tar
                                    for file in *; do mv -v ${file} Kubehunter_${file}; done
                                '''
                            } else {
                                error("Missing Kubehunter report")
                            }
                        }
                        archiveArtifacts allowEmptyArchive: true, artifacts: 'kubehunter-reports/**'
                    }
                }
                stage('Fetch Kubebench') {
                    steps {
                        script {
                            sh 'mkdir kubebench-reports'
                            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubebench/' + 'cenm-product-set-' + PRODUCT_SET + '-kubebench_results.tar'
                            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
                            report_found = !report_check.asBoolean()
                            if (report_found) {
                                sh '''
                                    cd kubebench-reports
                                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/kubebench/cenm-product-set-${PRODUCT_SET}-kubebench_results.tar
                                    tar -xvf cenm-product-set-${PRODUCT_SET}-kubebench_results.tar
                                    rm -f cenm-product-set-${PRODUCT_SET}-kubebench_results.tar
                                '''
                            } else {
                                error("Missing kubebench report")
                            }
                        }
                        archiveArtifacts allowEmptyArchive: true, artifacts: "kubebench-reports/**"
                    }
                }
            }
        }
        stage('Report') {
            steps {
                script {
                    sh '''
                        echo 'model_version: 2.0' >va_report_config.yaml
                        echo 'product_va_config:' >>va_report_config.yaml
                        echo '    name: cENM' >>va_report_config.yaml
                        echo '    product_name: cENM' >>va_report_config.yaml
                        echo "    version: ${PRODUCT_SET}" >>va_report_config.yaml
                        echo '    va_template_version: 2.0.0' >>va_report_config.yaml
                        echo '    description: cENM vulnerability analysis report' >>va_report_config.yaml
                    '''
                    def va_report_exit_code = sh (returnStatus: true, script: "${bob} va-report-generation")
                    if (va_report_exit_code == 3 || va_report_exit_code == 4 || va_report_exit_code == 5 ) {
                        unstable(message: "Report Stage is unstable")
                    } else if (va_report_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                    sh "curl -u ${PDUOSSVA_ARM_TOKEN} -X PUT -T Vulnerability_Report_2.0.md https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/aggregate_report/Vulnerability_Report_${PRODUCT_SET}.md"
                    archiveArtifacts artifacts: 'Vulnerability_Report_2.0.md', allowEmptyArchive: true
                }
            }
        }
    }
    post {
        always {
            script {
                try {
                    sh '''
                        curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/report-summary/summary-latest.csv
                        docker pull armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest
                        docker run --user $(id -u):$(id -g) --init --rm -v$(pwd):/va_summary armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest ./va_report.py ${CSAR_PACKAGE_VERSION} --user ${PDUOSSVA_ARM_TOKEN_USR} --token ${PDUOSSVA_ARM_TOKEN_PSW} --url https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/report-summary/
                    '''

                    archiveArtifacts artifacts: 'totals.csv', allowEmptyArchive: true
                    plot csvFileName: 'plot-2.csv', xmlSeries: [[file: 'a', nodeType: 'dd', url: 'dd', xpath: 'a']], propertiesSeries: [[file: 'totals.csv', label: 'v']], csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'totals.csv', inclusionFlag: 'OFF', url: '']], exclZero: false, group: 'test', keepRecords: false, logarithmic: false, numBuilds: '', style: 'line', title: 'Vulnerabilities', useDescr: false, yaxis: 'Vulnerabilities', yaxisMaximum: '', yaxisMinimum: ''
                    sh 'docker run --user $(id -u):$(id -g) --init --rm -v $(pwd):/va_summary armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest ./va_diff.py /va_summary/summary-latest.csv /va_summary/summary-${CSAR_PACKAGE_VERSION}.csv'
                    archiveArtifacts artifacts: 'added.csv', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'removed.csv', allowEmptyArchive: true
                } catch (Exception e) {
                    echo 'Generating summary, Exception occurred: ' + e.toString()
                }
                try {
                    sh '''
                        git clone ${GERRIT_MIRROR}/adp-fw/adp-fw-templates
                        cd adp-fw-templates
                        cp ../Vulnerability_Report_2.0.md .
                        git submodule update --init --recursive
                        sed -i s#user-guide-template/user_guide_template.md#Vulnerability_Report_2.0.md#g marketplace-config.yaml
                    '''
                    dir("adp-fw-templates") {
                        unstash "ruleset2.0.yaml"
                    }
                    sh "cd adp-fw-templates;${bob} clean init lint"
                    sh "cd adp-fw-templates;${bob} generate-docs"
                    sh "curl -u ${PDUOSSVA_ARM_TOKEN} -X PUT -T adp-fw-templates/build/marketplace/html/user-guide-template/Vulnerability_Report_2.0.html https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/aggregate_report/Vulnerability_Report_${PRODUCT_SET}.html"

                    archiveArtifacts artifacts: 'adp-fw-templates/build/marketplace/html/user-guide-template/Vulnerability_Report_2.0.html', allowEmptyArchive: true
                    publishHTML (target: [
                        allowMissing: false,
                        alwaysLinkToLastBuild: false,
                        keepAll: true,
                        reportDir: 'adp-fw-templates/build/marketplace/html/user-guide-template',
                        reportFiles: 'Vulnerability_Report_2.0.html',
                        reportName: "VA Report"
                    ])
                } catch (Exception e) {
                    echo 'Generating html report, Exception occurred: ' + e.toString()
                } finally {
                    deleteDir()
                }
            }
        }
        aborted {
            script {
                ci_va_send_email("Aborted")
            }
        }
        failure {
            script {
                ci_va_send_email("Failure")
            }
        }
        unstable {
            script {
                ci_va_send_email("Unstable")
            }
        }
    }
}

def kubeAuditImage(String image) {
    def IMAGE_NAME = image.split(':').first().split('/').last()
    def IMAGE_VERSION = image.split(':').last()
    def HELM_CHART_URL = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-helm/" + IMAGE_NAME + "/" + IMAGE_NAME + "-" + IMAGE_VERSION + ".tgz"
    try {
        def url = new java.net.URL(HELM_CHART_URL).openStream()
        url.close()
        IMAGES_KUBE.add(IMAGE_NAME)
    } catch (Exception e) {
        IMAGES_KUBE_FAIL[IMAGE_NAME] = "URLnotFound"
    }
}

def xrayConfig(String docker_image, String arm_server, String arm_server_xray) {
    xray_location = docker_image.replace(arm_server, arm_server_xray)
    xray_location = "\r\n    - \"" + xray_location + "\""
    xray_to_scan = xray_to_scan1 + xray_location

    writeFile file: "xray.config", text: xray_to_scan
    sh "${bobXray} xray-report-generation"
    xrayReport = readJSON file: 'xray_report.json'
    images = xrayReport['images']
}