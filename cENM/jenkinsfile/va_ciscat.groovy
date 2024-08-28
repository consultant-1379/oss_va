import groovy.json.JsonSlurper
@Library('ci-cn-pipeline-lib') _
@Library('ci-va-pipeline-lib') va
env.bob = new BobCommand()
    .envVars([
        CISCAT_BENCHMARK: '${BENCHMARK}'
    ])
    .needDockerSocket(true)
    .dockerEnvVars([
        DOCKER_GROUP: '${BOB_DOCKER_GROUP}',
        DOCKER_PASSWD: '${BOB_DOCKER_PASSWD}'
    ])
    .toString()

pipeline {
    agent {
        node {
            label 'VA_Openstack_CISCAT'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 12, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    environment {
        BOB_DOCKER_GROUP = '/etc/group'
        BOB_DOCKER_PASSWD = '/etc/passwd'
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
            agent {
                label 'VA'
            }
            steps {
                deleteDir()
                sh """
                    set +x
                    mkdir -p .bob
                    mkdir config
                """
                script {
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/ciscat/applicability_spec.json && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  config/applicability_spec.json'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                        sh 'FILE=cENM/ciscat/Dockerfile_add_packages && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  config/Dockerfile_add_packages'
                    } else {
                        sh 'set -C; FILE=cENM/ciscat/applicability_spec.json && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  config/applicability_spec.json || true'
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                        sh 'FILE=cENM/ciscat/Dockerfile_add_packages && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" > config/Dockerfile_add_packages'
                    }
                    stash includes: "*", name: "ruleset2.0.yaml", allowEmpty: true
                    stash includes: "config/", name: "applicability_spec.json", allowEmpty: true
                    stash includes: "config/", name: "Dockerfile_add_packages", allowEmpty: true

                    IMAGES = new ArrayList<String>();
                    ciscat_missing_report = "Images"
                    ciscat_faulty_report = "Images"
                    IMAGES_FAIL =  [:]

                    def csarContentsUrl = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-e2e-ci-generic-local/csar-contents/csar-metadata-artifacts-${PRODUCT_SET}.json"
                    def csarContentsObjectRaw = ["curl", "-H", "accept: application/json", "--url", "${csarContentsUrl}"].execute().text
                    def csarContentsObjectList = new JsonSlurper().parseText(csarContentsObjectRaw)

                    csarContentsObjectList.each {
                        if (it.toString().contains("dependent_docker_images=")) {
                            it."dependent_docker_images".each {
                                image = it.artifactory + it.name + ":" + it.version
                                image = image.replace('\'', '')
                                if (it.name == "eric-enm-sles-base" && it.version != "latest") {
                                    SLES_BASE_IMAGE = image
                                }
                                IMAGES.add(image)
                            }
                        }
                    }
                    csarContentsObjectList = null
                    csarContentsObjectRaw = null
                }
            }
        }
        stage('Get Common Base OS Version') {
            steps {
                script {
                    sh "docker pull ${SLES_BASE_IMAGE}"
                    CBOS_VERSION = sh(script: "docker inspect ${SLES_BASE_IMAGE} | grep -oP '(?<=\"common_base_os\": \")[^\"]*' | tail -1", returnStdout: true)
                    CBOS_VERSION = CBOS_VERSION.replaceAll("\\s", "")
                }
            }
        }
        stage('CIS-CAT Scan') {
            steps {
                script {
                    SCAN_STAGES = [:]
                    IMAGES.each { IMAGE ->
                        def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
                        def IMAGE_VERSION = IMAGE.split(':').last()
                        SCAN_STAGES["$IMAGE_NAME"] = {
                            node('VA_Openstack_CISCAT') {
                                stage("${IMAGE_NAME}") {
                                    script {
                                        withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                                            sh (returnStatus: true, script: "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json")
                                        }
                                        runScan(IMAGE, IMAGE_NAME, IMAGE_VERSION)
                                        def ret = sh (
                                            returnStatus: true,
                                            script: '''
                                                cd cis_cat_assessor/report_dir
                                                string_to_find="score"
                                                for file in ./*.json; do
                                                    if [ -s "${file}" ]; then
                                                        if grep -q "${string_to_find}" "${file}"; then
                                                            echo "Report Exist & Correct Report"
                                                        else
                                                            echo "Report Exist & Faulty Report"
                                                            exit 2
                                                        fi
                                                    else
                                                        echo "Report doesn't Exist"
                                                    fi
                                                done
                                            ''')
                                        if (ret == 2) {
                                            ciscat_faulty_report = ciscat_faulty_report + "\r\n " + IMAGE
                                            writeFile file: "ciscat_Faulty_Report.txt", text: ciscat_Faulty_Report
                                            archiveArtifacts artifacts: 'ciscat_Faulty_Report.txt', allowEmptyArchive: true
                                            currentBuild.result = 'FAILURE'
                                            sh 'exit -1'
                                        }
                                    }
                                }
                                deleteDir()
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
    unstash "Dockerfile_add_packages"
    try {
        withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}", "IMAGE=${IMAGE}"]) {
            unstash "applicability_spec.json"
            generate_report = ci_va_check_report("ciscat")
            if (generate_report) {
                if (LOCAL_ARTIFACTORY == true) {
                    IMAGE=IMAGE.replace("armdocker.rnd.ericsson.se", "ieatjfrepo1.athtem.eei.ericsson.se/hub")
                }
                writeFile(file: '.bob/var.image-to-scan', text: IMAGE)
                sh """
                    sed -i 's#<SERV_IMAGE>#${IMAGE}#g' config/Dockerfile_add_packages
                    sed -i 's#<CBO_VERSION>#${CBOS_VERSION}#g' config/Dockerfile_add_packages
                """
                sh "${bob} cis-cat-scan"
                if (LOCAL_ARTIFACTORY == true) {
                    sh '''
                        cd cis_cat_assessor/report_dir
                        sed -i 's#ieatjfrepo1.athtem.eei.ericsson.se/hub#armdocker.rnd.ericsson.se#g' *
                    '''
                }
                ci_va_push_report("ciscat", IMAGES_FAIL)
            } else {
                println("Report exists, skipping scan")
            }
        }
    } catch (Exception e) {
        println("Error with Image: " + IMAGE + " Error: " + e)
        ciscat_missing_report = ciscat_missing_report + "\r\n " + IMAGE
        writeFile file: "ciscat_Images_Not_Scanned.txt", text: ciscat_missing_report
        archiveArtifacts artifacts: 'ciscat_Images_Not_Scanned.txt', allowEmptyArchive: true
        unstable(message: "${IMAGE_NAME} is unstable")
    }
}