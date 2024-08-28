@Library('ci-cn-pipeline-lib') _
@Library('ci-va-pipeline-lib') va
env.bob = new BobCommand()
    .envVars([
        K8NAMESPACE: '${K8NAMESPACE}',
        ARTIFACTORY_USR: '${CREDENTIALS_SERO_ARTIFACTORY_USR}',
        ARTIFACTORY_PSW: '${CREDENTIALS_SERO_ARTIFACTORY_PSW}',
        DEFENSICS_HOME: '${DEFENSICS_HOME}'
    ])
    .needDockerSocket(true)
    .toString()

pipeline {
    agent {
        node {
            label 'VA_Openstack_Defensics'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 5, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    environment {
        DEFENSICS_HOME = '/home/lciadm100'
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
                sh 'cp cENM/ruleset/ruleset2.0.yaml .'
                script {
                    withCredentials([file(credentialsId: "${env.K8SCLUSTERID}" , variable: 'kubeconfig')]) {
                        writeFile file: '.kube/config', text: readFile(kubeconfig)
                    }
                    withCredentials([usernamePassword(credentialsId: 'enm_host', usernameVariable: 'ENM_USER', passwordVariable: 'ENM_PASSWORD')]) {
                        sh """
                            curl -c cookie.txt '${ENM_HOST_URL}/login' \
                                -H 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9' \
                                -H 'Accept-Language: en-US,en;q=0.9' \
                                -H 'Connection: keep-alive' \
                                -H 'Referer: ${ENM_HOST_URL}/login/successfullogon/' \
                                -H 'Sec-Fetch-Dest: document' \
                                -H 'Sec-Fetch-Mode: navigate' \
                                -H 'Sec-Fetch-Site: same-origin' \
                                -H 'Sec-Fetch-User: ?1' \
                                -H 'Upgrade-Insecure-Requests: 1' \
                                -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36' \
                                -H 'sec-ch-ua: "Chromium";v="106", "Google Chrome";v="106", "Not;A=Brand";v="99"' \
                                -H 'sec-ch-ua-mobile: ?0' \
                                -H 'sec-ch-ua-platform: "Windows"' \
                                --data 'IDToken1=${ENM_USER}&IDToken2=${ENM_PASSWORD}' \
                                --compressed \
                                --insecure \

                            unzip \${DEFENSICS_HOME}/Defensics/testplans/web-app.testplan -d temp_for_zip_extract
                            new_cookie=\$(grep -r cookie.txt -e 'iPlanetDirectoryPro' | awk -F"iPlanetDirectoryPro" '/iPlanetDirectoryPro/{print \$2}' | xargs)

                            for file in \$(grep -rl temp_for_zip_extract/ -e 'iPlanetDirectoryPro')
                            do
                                old_cookie=\$(grep -r \$file -e 'iPlanetDirectoryPro' | awk -F"iPlanetDirectoryPro=" '/iPlanetDirectoryPro/{print \$2}' | xargs)
                                sed -i "s/\$old_cookie/\$new_cookie/g" \$file
                            done
                            cd temp_for_zip_extract && zip -r web-app.testplan * && sudo cp web-app.testplan \${DEFENSICS_HOME}/Defensics/testplans/
                        """
                    }
                }
            }
        }
        stage('Defensics Scan') {
            steps {
                script {
                    def scan_exit_code = sh (returnStatus: true, script: """docker run --rm --user 1001:1001 --env DEFENSICS_HOME=${DEFENSICS_HOME} -w ${DEFENSICS_HOME} -v ${DEFENSICS_HOME}:${DEFENSICS_HOME} -v ::rw armdocker.rnd.ericsson.se/proj-adp-cicd-drop/defensics.cbo:latest run-defensics -s web-app -t ${DEFENSICS_HOME}/Defensics/testplans/web-app.testplan -r ${WORKSPACE}/""")
                    if (scan_exit_code != 0) {
                        currentBuild.result = "FAILURE"
                        sh 'exit -1'
                    }
                    def ret = sh (
                        returnStatus: true,
                        script: '''
                            filename=$(find . -name "super-summary.xml" 2>/dev/null)
                            if [ -z "$filename" ]; then
                                echo "Report doesn't Exist"
                            else
                                if grep -q "run-time" "$filename"; then
                                    echo "Report Exist & Correct Report"
                                else
                                    echo "Report Exist & Faulty Report"
                                    exit 2
                                fi
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
                    sh "find ${WORKSPACE}/*/*/log -name 'super-summary.xml' -exec cp {} report.xml \\;"
                    archiveArtifacts '**/report.html, **/report.xml'
                    IMAGES_FAIL = [:]
                    env.IMAGE_NAME = 'cenm-product-set'
                    env.IMAGE_VERSION = env.PRODUCT_SET
                    ci_va_push_report('defensics', IMAGES_FAIL)
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