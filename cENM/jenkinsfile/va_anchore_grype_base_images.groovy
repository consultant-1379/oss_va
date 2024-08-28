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
        timeout(time: 5, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    environment {
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
        stage('Anchore Grype Scan') {
            steps {
                sh '''
                    set +x
                    mkdir -p .bob
                '''
                script {
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    }
                    stash includes: "*", name: "ruleset2.0.yaml", allowEmpty: true

                    IMAGES = new ArrayList<String>();
                    anchore_missing_report = "Images"
                    anchore_faulty_report = "Images"
                    IMAGES_FAIL =  [:]

                    def pull_baseimages_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/base_images/base_images_list_${PRODUCT_SET}.txt -o base_images_list.txt'
                    def pull_baseimages_output = sh(script: pull_baseimages_command, returnStdout: true, returnStatus: true)

                    IMAGES = readFile('base_images_list.txt').readLines()

                    SCAN_STAGES = [:]
                    IMAGES.each { IMAGE ->
                        def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                        def IMAGE_VERSION = IMAGE.split(':').last()
                        SCAN_STAGES["$IMAGE_NAME"] = {
                            node('VA') {
                                stage("${IMAGE_NAME}") {
                                    script {
                                        withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                                            sh (returnStatus: true, script: "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json")
                                        }
                                        runScan(IMAGE,IMAGE_NAME,IMAGE_VERSION)
                                        def ret = sh (
                                            returnStatus: true,
                                            script: '''
                                                cd anchore-grype-reports
                                                if [ -f *-vuln.json ] && [ -s *-vuln.json ]; then
                                                    if grep -q "vulnerabilities" *-vuln.json; then
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
                                            anchore_faulty_report = anchore_faulty_report + "\r\n " + IMAGE
                                            writeFile file: "Anchore_Faulty_Report.txt", text: anchore_faulty_report
                                            archiveArtifacts artifacts: 'Anchore_Faulty_Report.txt', allowEmptyArchive: true
                                            currentBuild.result = 'FAILURE'
                                            sh 'exit -1'
                                        }
                                    }
                                    deleteDir()
                                }
                            }
                        }
                    }
                    parallel SCAN_STAGES
                }
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

def runScan(String IMAGE, String IMAGE_NAME, String IMAGE_VERSION) {
    deleteDir()
    unstash "ruleset2.0.yaml"
    try {
        withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}", "IMAGE=${IMAGE}"]) {
            sh '''
                mkdir -p .bob
                echo 'json' > .bob/var.report-format
            '''
            if (LOCAL_ARTIFACTORY == true) {
                IMAGE_LOCAL=IMAGE.replace("armdocker.rnd.ericsson.se", "ieatjfrepo1.athtem.eei.ericsson.se/hub")
                writeFile(file: '.bob/var.image-to-scan', text: IMAGE_LOCAL)
            } else {
                writeFile(file: '.bob/var.image-to-scan', text: IMAGE)
            }
            def generate_report = ci_va_check_report("anchore_grype")
            if (generate_report) {
                def scan_exit_code = sh (returnStatus: true, script: "${bob} anchore-grype-scan")
                if (scan_exit_code != 0) {
                    currentBuild.result = "FAILURE"
                    sh 'exit -1'
                }
                sh 'docker rmi ${IMAGE}||true'
                if (LOCAL_ARTIFACTORY == true) {
                    sh '''
                        cd anchore-grype-reports
                        sed -i 's#ieatjfrepo1.athtem.eei.ericsson.se/hub#armdocker.rnd.ericsson.se#g' *
                    '''
                }
                ci_va_push_report("anchore_grype", IMAGES_FAIL)
            } else {
                println("Report exists, skipping scan")
            }
        }
    } catch (Exception e) {
        println("Error with Image: " + IMAGE + " Error: " + e)
        anchore_missing_report = anchore_missing_report + "\r\n " + IMAGE
        writeFile file: "Anchore_Images_Not_Scanned.txt", text: anchore_missing_report
        archiveArtifacts artifacts: 'Anchore_Images_Not_Scanned.txt', allowEmptyArchive: true
        currentBuild.result = 'UNSTABLE'
        unstable(message: "${IMAGE_NAME} is unstable")
    }
}