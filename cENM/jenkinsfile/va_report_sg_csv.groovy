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
                        sh 'FILE=scripts/rpmVAenrichment.py && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  rpmVAenrichment.py'
                    } else {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                        sh 'FILE=scripts/rpmVAenrichment.py && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  rpmVAenrichment.py'
                    }

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
                            writeJSON file: 'xray_report.json', json: xrayReport ,pretty:2
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
                    def va_report_exit_code = sh (returnStatus: true, script: "${bob} va-report-csv-generation")
                    if (va_report_exit_code == 3 || va_report_exit_code == 4 || va_report_exit_code == 5 ) {
                        unstable(message: "Report Stage is unstable")
                    } else if (va_report_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                    archiveArtifacts artifacts: 'Vulnerability_Report_2.0.csv', allowEmptyArchive: true
                }
            }
        }
        stage('Enrich CSV VA Report') {
            steps {
                sh """
                    . /home/axisadm/cENM_va/311env/bin/activate
                    python -u ./rpmVAenrichment.py Vulnerability_Report_2.0.csv Vulnerability_Report_RPM_SG_2.0.csv
                """
                sh "curl -u ${PDUOSSVA_ARM_TOKEN} -X PUT -T Vulnerability_Report_RPM_SG_2.0.csv https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/aggregate_report/Vulnerability_Report_SG_${PRODUCT_SET}.csv"
                archiveArtifacts artifacts: 'Vulnerability_Report_RPM_SG_2.0.csv', allowEmptyArchive: true
            }
        }
    }
    post {
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

def xrayConfig(String docker_image, String arm_server, String arm_server_xray) {
    xray_location = docker_image.replace(arm_server, arm_server_xray)
    xray_location = "\r\n    - \"" + xray_location + "\""
    xray_to_scan = xray_to_scan1 + xray_location

    writeFile file: "xray.config", text: xray_to_scan
    sh "${bobXray} xray-report-generation"
    xrayReport = readJSON file: 'xray_report.json'
    images = xrayReport['images']
}