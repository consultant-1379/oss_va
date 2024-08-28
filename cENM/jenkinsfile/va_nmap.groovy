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
        timeout(time: 10, unit: 'HOURS')
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
                echo 'Inject k8s config file:'
                script {
                    withCredentials([file(credentialsId: K8SCLUSTERID, variable: 'kubeconfig')]) {
                        writeFile file: '.kube/config', text: readFile(kubeconfig)
                    }
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'cp cENM/ruleset/ruleset2.0.yaml .'
                    }
                }
                sh "${bob} nmap-scan:clean"
            }
        }
        stage('Generate Config') {
            steps {
                script {
                    //podChunkSize will run scans in chunks. otherwise all containers will be scanned at once.
                    def podChunkSize = 40
                    echo "Scanning all containers"
                    sh "${bob} nmap-scan:get-pods"
                    services = readFile(file: '.bob/var.pods').split('\n')
                    servicesCollated = services.toList().collate(podChunkSize)
                    servicesCollated.eachWithIndex { val, idx ->
                        println "$val in position $idx"
                        env.FILENAME = "nmapConfigFile_" + idx
                        sh "echo 'nmapConfig:'>${FILENAME}"
                        sh "    echo '  services:'>>${FILENAME}"
                        for (service in val) {
                            env.SERVICE = service
                            sh "cat ./cENM/nmap/nmap_template.yaml |sed -e 's/SERVICE_NAME/${SERVICE}/'>>${FILENAME}"
                            sh "echo ''>>${FILENAME}"
                        }
                        sh "echo '  reportDir : \"nmap_reports\"'>>${FILENAME}"
                    }
                }
            }
        }
        stage('NMAP Scan') {
            steps {
                script {
                    servicesCollated.eachWithIndex { val, idx ->
                        env.FILENAME = "nmapConfigFile_" + idx
                        sh "cp ${FILENAME} nmap_config.yaml"
                        def scan_exit_code = sh (returnStatus: true, script: "${bob} nmap-scan:scan")
                        if (scan_exit_code != 0) {
                            currentBuild.result = "FAILURE"
                            sh 'exit -1'
                        }
                    }
                    def ret = sh (
                        returnStatus: true,
                        script: '''
                            cd nmap_reports/nmap_report/
                            string_to_find="runstats"
                            if ls *.xml 1>/dev/null 2>&1; then
                               for file in *.xml; do
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
                    archiveArtifacts 'nmap_reports/nmap_report/**.*'
                    archiveArtifacts 'nmapConfigFile_*'
                    IMAGES_FAIL = [:]
                    env.IMAGE_NAME = 'cenm-product-set'
                    env.IMAGE_VERSION = env.PRODUCT_SET
                    ci_va_push_report('nmap', IMAGES_FAIL)
                    sh 'rm -rf .kube/config'
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
