#!/usr/bin/env groovy
@Library('ci-cn-pipeline-lib') _
@Library('ci-va-pipeline-lib') va
env.bob = new BobCommand()
    .envVars([
        KUBECONFIG:'${KUBECONFIG}',
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
    environment {
        KUBECONFIG = "${WORKSPACE}/.kube/config"
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Checkout OSS VA Git Repository') {
            steps {
                git branch: env.BRANCH,
                    url: '${GERRIT_MIRROR}/' + env.REPO
                sh 'git remote set-url origin --push ${GERRIT_CENTRAL}/${REPO}'
            }
        }
        stage('Init') {
            steps {
                script {
                    withCredentials([file(credentialsId: K8SCLUSTERID, variable: 'kubeconfig')]) {
                        writeFile file: '.kube/config', text: readFile(kubeconfig)
                    }
                }
                sh '''
                    cp cENM/ruleset/ruleset2.0.yaml .
                    cp cENM/robot/test-suite.txt .
                    cp dockerfiles/va_report/va_report.txt .
                    cp cENM/robot/va_report_robot.txt .
                '''
            }
        }
        stage('Generate Config') {
            steps {
                script {
                    sh "sed -i 's#BASE_URL#${BASE_URL}#' test-suite.txt"
                }
            }
        }
        stage('Robot Framework Scan') {
            steps {
                script {
                    def scan_exit_code = sh (returnStatus: true, script: "${bob} robot-framework-scan")
                    if (scan_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '.bob/*.xml'
            archiveArtifacts artifacts: '.bob/*.html',
            onlyIfSuccessful: false
            publishHTML (target: [
                allowMissing: false,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: "${WORKSPACE}/.bob",
                reportFiles: 'log.html',
                reportName: "RobotFramework Log"
            ])
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