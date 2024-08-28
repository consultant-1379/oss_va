@Library('ci-cn-pipeline-lib') _
@Library('ci-va-pipeline-lib') va
env.bob = new BobCommand()
    .envVars([
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
                    sh '''
                        mkdir -p "${PWD}/reports/"
                        chmod 760 "${PWD}/reports/"
                        mkdir config
                    '''
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/config/kubebench_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  config/kubebench_config.yaml'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'FILE=cENM/config/kubebench_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  config/kubebench_config.yaml'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    }
                    sh 'sed -i "s#K8NAMESPACE#${K8NAMESPACE}#g" config/kubebench_config.yaml'
                    withCredentials([file(credentialsId: K8SCLUSTERID , variable: 'KUBECONFIG')]) {
                        writeFile file: '.kube/config', text: readFile(KUBECONFIG)
                    }
                }
            }
        }
        stage('Kubebench Scan') {
            steps {
                script {
                    def scan_exit_code = sh (returnStatus: true, script: "${bob} kubebench-scan")
                    if (scan_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                    }
                    def ret = sh (
                        returnStatus: true,
                        script: '''
                            cd reports
                            filename=$(find . -type f -name 'kubebench_results_*' ! -name '*.txt' -print -quit)
                            if [ -n "$filename" ]; then
                                if [ -s "$filename" ] && grep -q "Summary total" "$filename"; then
                                    echo "Report Exist & Correct Report"
                                else
                                    echo "Report Exist & Faulty Report"
                                    exit 2
                                fi
                            else
                                echo "Report doesn't Exist"
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
                    ci_va_push_report('kubebench', IMAGES_FAIL)
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