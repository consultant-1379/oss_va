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
                    mkdir config
                """
                script {
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/config/kubeaudit_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  config/kubeaudit_config.yaml'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'FILE=cENM/config/kubeaudit_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  config/kubeaudit_config.yaml'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    }
                    stash includes: "*", name: "ruleset2.0.yaml", allowEmpty: true
                    stash includes: "config/", name: "kubeaudit_config.yaml", allowEmpty: true

                    IMAGES = new ArrayList<String>();
                    IMAGES_KUBE = new ArrayList<String>();
                    IMAGES_KUBE_REPORT = new ArrayList<String>();
                    kube_missing_report = "Images"
                    kube_faulty_report = "Images"
                    kube_skipped_images = "Images"
                    IMAGES_FAIL = [:]

                    def csarContentsUrl = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-e2e-ci-generic-local/csar-contents/csar-metadata-artifacts-${PRODUCT_SET}.json"
                    def csarContentsObjectRaw = ["curl", "-H", "accept: application/json", "--url", "${csarContentsUrl}"].execute().text
                    def csarContentsObjectList = new JsonSlurper().parseText(csarContentsObjectRaw)

                    csarContentsObjectList.each {
                        if (it.toString().contains("dependent_docker_images=")) {
                            it."dependent_docker_images".each {
                                image = it.artifactory + it.name + ":" + it.version
                                image = image.replace('\'', '')
                                IMAGES.add(image)
                                kubeImage(image)
                            }
                        }
                    }
                    csarContentsObjectList = null
                    csarContentsObjectRaw = null

                    SCAN_STAGES = [:]
                    IMAGES.each { IMAGE ->
                        def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                        def IMAGE_VERSION = IMAGE.split(':').last()
                        SCAN_STAGES["$IMAGE_NAME"] = {
                            node('VA') {
                                stage("${IMAGE_NAME}") {
                                    script {
                                        if (IMAGES_KUBE.contains(IMAGE_NAME)) {
                                            runScan(IMAGE, IMAGE_NAME, IMAGE_VERSION)
                                            def ret = sh (
                                                returnStatus: true,
                                                script: '''
                                                    cd reports/arm_charts/'''+IMAGE_NAME+'''/templates/
                                                    if ls * 1>/dev/null 2>&1; then
                                                        for file in *; do
                                                            if [ -s "$file" ]; then
                                                                if grep -q "AuditResultName" "$file"; then
                                                                    echo "Correct Report"
                                                                else
                                                                    echo "Faulty Report"
                                                                    exit 2
                                                                fi
                                                            else
                                                                echo "File \"$file\" is empty."
                                                            fi
                                                        done
                                                    else
                                                        echo "No files present in the current directory."
                                                    fi
                                                ''')
                                            if (ret == 2) {
                                                kube_faulty_report = kube_faulty_report + "\r\n " + IMAGE
                                                writeFile file: "Kubeaudit_Faulty_Reports.txt", text: kube_faulty_report
                                                archiveArtifacts artifacts: 'Kubeaudit_Faulty_Reports.txt', allowEmptyArchive: true
                                                currentBuild.result = 'FAILURE'
                                                sh 'exit -1'
                                            }
                                        } else {
                                            echo "HELM Chart URL Not Found for ${IMAGE}"
                                            kube_skipped_images = kube_skipped_images + "\r\n " + IMAGE
                                            writeFile file: "Kubeaudit_Images_Not_Scanned.txt", text: kube_skipped_images
                                            archiveArtifacts artifacts: 'Kubeaudit_Images_Not_Scanned.txt', allowEmptyArchive: true
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
    withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
        deleteDir()
        unstash "ruleset2.0.yaml"
        sh '''
            rm -rf reports
            mkdir reports
        '''
        unstash "kubeaudit_config.yaml"
        def HELM_CHART_URL = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-helm/" + IMAGE_NAME + "/" + IMAGE_NAME + "-" + IMAGE_VERSION + ".tgz"
        sh "sed -i 's#HELM_CHART_URL#${HELM_CHART_URL}#g' config/kubeaudit_config.yaml"
        generate_report = ci_va_check_report("kubeaudit")
        if (generate_report) {
            def scan_exit_code = sh (returnStatus: true, script: "${bob} kubeaudit-scan")
            if (scan_exit_code != 0) {
                currentBuild.result = "FAILURE"
                sh 'exit -1'
            }
            try {
                ci_va_push_report("kubeaudit", IMAGES_FAIL)
            } catch (Exception e) {
                IMAGES_FAIL[image] = "NoReport"
                println("Error with Image: " + IMAGE + " Error: " + e)
                kube_missing_report = kube_missing_report + "\r\n " + IMAGE
                writeFile file: "kube_Images_Not_Scanned.txt", text: kube_missing_report
                varchiveArtifacts artifacts: 'kube_Images_Not_Scanned.txt', allowEmptyArchive: true
                unstable(message: "${IMAGE_NAME} is unstable")
            }
        } else {
            println("Report exists, skipping scan")
        }
    }
}

def kubeImage(String image) {
    def IMAGE_NAME = image.split(':').first().split('/').last()
    def IMAGE_VERSION = image.split(':').last()
    def HELM_CHART_URL = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-helm/" + IMAGE_NAME + "/" + IMAGE_NAME + "-" + IMAGE_VERSION + ".tgz"
    try {
        def url = new java.net.URL(HELM_CHART_URL).openStream()
        url.close()
        IMAGES_KUBE.add(IMAGE_NAME)
    } catch (Exception e) {
        IMAGES_FAIL[IMAGE_NAME] = "URLnotFound"
    }
}
