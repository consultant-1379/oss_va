@Library('ci-cn-pipeline-lib') _
@Library('ci-va-pipeline-lib') va
env.bob = new BobCommand()
    .envVars([
        ARTIFACTORY_USR: '${CREDENTIALS_SERO_ARTIFACTORY_USR}',
        ARTIFACTORY_PSW: '${CREDENTIALS_SERO_ARTIFACTORY_PSW}',
        K8NAMESPACE: '${K8NAMESPACE}'
    ])
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
        timeout(time: 5, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    environment {
        CREDENTIALS_SERO_ARTIFACTORY = credentials('osscnciArtifactoryAccessTokenSERO')
    }
    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    currentBuild.displayName = "#${env.BUILD_NUMBER}-${env.PRODUCT_SET}"
                }
            }
        }
        stage('Checkout OSS VA Git Repository') {
            steps {
                script {
                    git changelog: true, poll: false, url: '${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va'
                }
            }
        }
        stage('Init') {
            steps {
                script {
                    withCredentials([file(credentialsId: K8SCLUSTERID, variable: 'kubeconfig')]) {
                        writeFile file: '.kube/config', text: readFile(kubeconfig)
                    }
                    withCredentials([file(credentialsId: 'enm-zap-context', variable: 'enmcontext')]) {
                        writeFile file: 'enmlogin.context', text: readFile(enmcontext)
                    }
                }
                sh '''
                    cp cENM/ruleset/ruleset2.0.yaml .
                    cp cENM/zap/zap_config.yaml .
                '''
                sh "${bob} zap-scan:clean"
            }
        }
        stage('Generate Config') {
            steps {
                script {
                    sh "sed -i 's#BASE_URL#${BASE_URL}#' zap_config.yaml"
                }
            }
        }
        stage('ZAP Scan') {
            steps {
                script {
                    def scan_exit_code = sh (returnStatus: true, script: "${bob} zap-scan:scan")
                    if (scan_exit_code == 0 || scan_exit_code == 2) {
                        // Note: Zap exit code 2 means there is at least one WARN and no FAILs
                    } else if (scan_exit_code == 1 || scan_exit_code == 3) {
                        currentBuild.result = "UNSTABLE"
                    } else {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                    archiveArtifacts 'zap/reports/**.*'
                    sh 'cp zap/reports/BaseURL_full.json .'
                    def ret = sh (
                        returnStatus: true,
                        script: '''
                            cd zap/reports
                            if [ -f BaseURL_full.json ] && [ -s BaseURL_full.json ]; then
                                if grep -q "uri" BaseURL_full.json; then
                                    echo "Correct Report"
                                else
                                    echo "Faulty Report"
                                    exit 2
                                fi
                            else
                                echo "Report not found or empty"
                            fi
                        ''')
                    if (ret == 2) {
                        currentBuild.result = 'FAILURE'
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                try {
                    IMAGES_FAIL = [:]
                    env.IMAGE_NAME = 'cenm-product-set'
                    env.IMAGE_VERSION = env.PRODUCT_SET
                    ci_va_push_report('zap', IMAGES_FAIL)
                } catch (Exception e) {
                    println("Error running post steps: " + e)
                }
                deleteDir()
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
