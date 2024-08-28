@Library('ci-cn-pipeline-lib') _
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
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    environment {
        PDUOSSVA_ARM_TOKEN = credentials('pduossvaArtifactoryAccessTokenSELI')
        XRAY = credentials('osscnciArtifactoryAccessTokenSELI')
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Init') {
            steps {
                script {
                    sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    stash includes: "*", name: "ruleset2.0.yaml", allowEmpty: true
                    env.VERSION = RHEL_LSB_IMAGE.split(':')[1]
                }
            }
        }
        stage('Trivy Scan') {
            steps {
                sh '''
                    set +x
                    mkdir -p .bob
                    mkdir -p trivy-reports
                '''
                script {
                    sh '''
                        mkdir -p .bob
                        echo 'json' > .bob/var.report-format
                    '''
                    writeFile(file: '.bob/var.image-to-scan', text: RHEL_LSB_IMAGE)
                    sh "${bob} trivy-scan"
                    sh 'mv trivy_scan.json ./trivy-reports/trivy_scan_lsb.json'
                    archiveArtifacts artifacts: 'trivy-reports/trivy_scan_lsb.json', allowEmptyArchive: true
                    writeFile(file: '.bob/var.image-to-scan', text: RHEL_JBOSS_IMAGE)
                    sh "${bob} trivy-scan"
                    sh 'mv trivy_scan.json ./trivy-reports/trivy_scan_jboss.json'
                    archiveArtifacts artifacts: 'trivy-reports/trivy_scan_jboss.json', allowEmptyArchive: true
                }
            }
        }
        stage('Anchore Grype Scan') {
            steps {
                writeFile(file: '.bob/var.image-to-scan', text: RHEL_LSB_IMAGE)
                sh "${bob} anchore-grype-scan"

                writeFile(file: '.bob/var.image-to-scan', text: RHEL_JBOSS_IMAGE)
                sh "${bob} anchore-grype-scan"
            }
        }
        stage('XRAY Scan') {
            steps {
                script {
                    bobXray = new BobCommand()
                        .envVars([
                            'XRAY_USR': env.XRAY_USR,
                            'XRAY_PSW': env.XRAY_PSW,
                        ])
                        .needDockerSocket(true)
                        .toString()

                    xray_to_scan = "xray:\r\n  name: RHEL\r\n  version: Various\r\n  paths:"
                    xray_location = RHEL_LSB_IMAGE.replace('armdocker.rnd.ericsson.se', 'ARM-SELI/proj-enm-docker-global')
                    xray_location = "\r\n    - \"" + xray_location + "\""
                    xray_location = RHEL_JBOSS_IMAGE.replace('armdocker.rnd.ericsson.se', 'ARM-SELI/proj-enm-docker-global')
                    xray_location = "\r\n    - \"" + xray_location + "\""
                    xray_to_scan = xray_to_scan + xray_location

                    writeFile file: "xray.config", text: xray_to_scan
                    archiveArtifacts artifacts: 'xray.config', allowEmptyArchive: true
                    sh "${bobXray} xray-report-generation"
                    archiveArtifacts artifacts: 'xray_report.json', allowEmptyArchive: true
                }
            }
        }
        stage('Report') {
            steps {
                script {
                    sh '''
                        echo 'model_version: 2.0' >va_report_config.yaml
                        echo 'product_va_config:' >>va_report_config.yaml
                        echo '    name: RHEL LSB and JBOSS' >>va_report_config.yaml
                        echo '    product_name: RHEL LSB and JBOSS' >>va_report_config.yaml
                        echo "    version: ${VERSION}" >>va_report_config.yaml
                        echo '    va_template_version: 2.0.0' >>va_report_config.yaml
                        echo '    description: RHEL LSB and JBOSS vulnerability analysis report' >>va_report_config.yaml
                    '''
                    sh "${bob} va-report-static-generation"
                }
            }
        }
    }
    post {
        always {
            script {
                try {
                    sh 'docker rmi ${RHEL_LSB_IMAGE}||true'
                    sh 'docker rmi ${RHEL_JBOSS_IMAGE}||true'
                    sh '''
                        curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/report-summary/rhel/summary-latest.csv
                    '''
                    sh 'docker pull armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest'
                    sh 'docker run --user $(id -u):$(id -g) --init --rm -v$(pwd):/va_summary armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest ./va_report.py ${VERSION} --user ${PDUOSSVA_ARM_TOKEN_USR} --token ${PDUOSSVA_ARM_TOKEN_PSW} --url https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/report-summary/rhel/'
                    archiveArtifacts artifacts: 'totals.csv', allowEmptyArchive: true
                    plot csvFileName: 'plot-2.csv', xmlSeries: [[file: 'a', nodeType: 'dd', url: 'dd', xpath: 'a']], propertiesSeries: [[file: 'totals.csv', label: 'v']], csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'totals.csv', inclusionFlag: 'OFF', url: '']], exclZero: false, group: 'test', keepRecords: false, logarithmic: false, numBuilds: '', style: 'line', title: 'Vulnerabilities', useDescr: false, yaxis: 'Vulnerabilities', yaxisMaximum: '', yaxisMinimum: ''

                    sh 'docker run --user $(id -u):$(id -g) --init --rm -v $(pwd):/va_summary armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest ./va_diff.py /va_summary/summary-latest.csv /va_summary/summary-${VERSION}.csv'
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

                    archiveArtifacts artifacts: 'Vulnerability_Report_2.0.md', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'adp-fw-templates/build/marketplace/html/user-guide-template/Vulnerability_Report_2.0.html', allowEmptyArchive: true
                } catch (Exception e) {
                    echo 'Generating html report, Exception occurred: ' + e.toString()
                } finally {
                    deleteDir()
                }
            }
        }
    }
}
