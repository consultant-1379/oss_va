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
            label "VA"
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 10, unit: 'HOURS')
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
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/tenable/nessus.py && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" > nessus.py'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh '''
                            cp cENM/ruleset/ruleset2.0.yaml .
                            cp cENM/tenable/nessus.py .
                        '''
                    }
                    withCredentials([file(credentialsId: "${env.K8SCLUSTERID}", variable: 'kubeconfig')]) {
                        writeFile file: ".kube/config", text: readFile(kubeconfig)
                    }
                    withCredentials([file(credentialsId: "tenablesc-secrets", variable: 'tenablescsecrets')]) {
                        writeFile file: "tenablesc-secrets.yaml", text: readFile(tenablescsecrets)
                    }
                    withCredentials([file(credentialsId: "nessus-sc", variable: 'nessussc')]) {
                        writeFile file: "nessus-sc.conf", text: readFile(nessussc)
                    }
                }
            }
        }
        stage('Tenable Scan') {
            steps {
                script {
                    def scan_exit_code = sh (returnStatus: true, script: "${bob} tenable-scan")
                    if (scan_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                    def ret = sh (
                        returnStatus: true,
                        script: '''
                            cd nessus_reports/Tenable
                            string_to_find="Severity"
                            if ls *.csv 1>/dev/null 2>&1; then
                               for file in *.csv; do
                                    if [ -f "$file" ]; then
                                        if grep -q "$string_to_find" "$file"; then
                                            echo "Report Exist & Correct Report"
                                        else
                                            echo "Report Exist & Faulty Report"
                                            exit 2
                                        fi
                                    fi
                                done
                            else
                                echo "Report doesn't Exist"
                            fi
                        ''')
                    if (ret == 2) {
                        currentBuild.result = 'FAILURE'
                        sh 'exit -1'
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                try {
                    archiveArtifacts '**/*.pdf,**/*.csv'
                    IMAGES_FAIL = [:]
                    env.IMAGE_NAME = 'cenm-product-set'
                    env.IMAGE_VERSION = env.PRODUCT_SET
                    ci_va_push_report('tenable', IMAGES_FAIL)
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