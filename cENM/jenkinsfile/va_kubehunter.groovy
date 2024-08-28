#!/usr/bin/env groovy
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
                    sh 'mkdir config'
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/config/kubehunter_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  config/kubehunter_config.yaml'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'FILE=cENM/config/kubehunter_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  config/kubehunter_config.yaml'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    }
                    withCredentials([file(credentialsId: K8SCLUSTERID , variable: 'KUBECONFIG')]) {
                        writeFile file: '.kube/config', text: readFile(KUBECONFIG)
                    }
                }
            }
        }
        stage('Get IPs to Scan') {
            steps {
                script {
                    sh "${bob} kubehunter-scan:get-node-ip"
                    def IP_ADDRESS = sh(script: "cat .bob/var.node-ip", returnStdout: true)
                    IP_ADDRESS = IP_ADDRESS.replaceAll("\\s", "")
                    sh "sed -i 's#IP_ADDRESS#${IP_ADDRESS}#g' config/kubehunter_config.yaml"
                }
            }
        }
        stage('Kubehunter Scan') {
            steps {
                script {
                    def scan_exit_code = sh (returnStatus: true, script: "${bob} kubehunter-scan:scan")
                    if (scan_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                    def ret = sh (
                        returnStatus: true,
                        script: '''
                            cd reports
                            if [ $(find . -maxdepth 1 -type f ! -name "*.*" -name "*_results" | wc -l) -eq 0 ]; then
                                echo "Report doesn't Exist"
                            else
                                for file in $(find . -maxdepth 1 -type f ! -name "*.*" -name "*_results")
                                do
                                    if grep -q "vulnerabilities" "$file"; then
                                        echo "Report Exist & Correct Report"
                                    else
                                        echo "Report Exist & Faulty Report"
                                        exit 2
                                    fi
                                done
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
                    IMAGES_FAIL = [:]
                    env.IMAGE_NAME = 'cenm-product-set'
                    env.IMAGE_VERSION = env.PRODUCT_SET
                    ci_va_push_report('kubehunter', IMAGES_FAIL)
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