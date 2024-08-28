import groovy.json.JsonSlurper
@Library('ci-cn-pipeline-lib') _

pipeline {
    agent {
        node {
            label 'VA'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
        timeout(time: 48, unit: 'HOURS')
    }
    environment {
        XRAY = credentials('osscnciArtifactoryAccessTokenSELI')
        RUN_VA = credentials('osscnciArtifactoryPasswordSELI')
        PDUOSSVA_ARM_TOKEN = credentials('pduossvaArtifactoryAccessTokenSELI')

        VA_REPORT_LOCATION = '".\\/input_tables\\/final_report.csv"'
        VA_REPORT_SED = "'/^  va_report: /s/:.*\$/: ${VA_REPORT_LOCATION}/' config_properties.yml"

        CXP_TABLE_LOCATION = '".\\/db_input_tables\\/CXP_Table_ORIGINAL_No_Duplicates.xlsx"'
        CXP_TABLE_SED = "'/^  cxp_table: /s/:.*\$/: ${CXP_TABLE_LOCATION}/' config_properties.yml"

        SG_MAPPING_LOCATION = '".\\/db_input_tables\\/SG_Mapping_Table.xlsx"'
        SG_MAPPING_SED = "'/^  sg_mapping_table: /s/:.*\$/: ${SG_MAPPING_LOCATION}/' config_properties.yml"

        FOSS_LOCATION = '".\\/db_input_tables\\/FOSS_MR_CVE+SG+3pp_Baseline_Table.xlsx"'
        FOSS_SED = "'/^  foss_cve_sg_3pp_table: /s/:.*\$/: ${FOSS_LOCATION}/' config_properties.yml"

        TEAM_LOCATION = '".\\/db_input_tables\\/Team_Inventory_Export.xls"'
        TEAM_SED = "'/^  team_inv_table: /s/:.*\$/: ${TEAM_LOCATION}/' config_properties.yml"

        TEAM_C_LOCATION = '".\\/clean_db_input_tables\\/Team_Inventory_Export.xlsx"'
        TEAM_C_SED = "'/^  team_inv_table_clean: /s/:.*\$/: ${TEAM_C_LOCATION}/' config_properties.yml"

        BASE_IMAGE_LOCATION = '".\\/input_tables\\/base-images-final_report.csv"'
        BASE_IMAGE_SED = "'/^  base_image: /s/:.*\$/: ${BASE_IMAGE_LOCATION}/' config_properties.yml"

    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Init') {
            steps {
                sh """
                    mkdir ./input_tables
                    mkdir ./db_input_tables
                    cd db_input_tables
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/cve_jira_files/CXP_Table_ORIGINAL_No_Duplicates.xlsx
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/cve_jira_files/FOSS_MR_CVE+SG+3pp_Baseline_Table.xlsx
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/cve_jira_files/Fake_CVE.xlsx
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/cve_jira_files/SG_Mapping_Table.xlsx
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/cve_jira_files/Team_Inventory_Export.xls
                """
                script {
                    drop_values = String.valueOf(PRODUCT_SET).tokenize('.')
                    env.DROP = drop_values[0] + "." + drop_values[1]
                    product_set = String.valueOf(PRODUCT_SET).split('-')[0]
                    curl_response = sh(script: 'curl -L -s https://ci-portal.seli.wh.rnd.internal.ericsson.com/api/deployment/deploymentutilities/productSet/ENM/version/' + "$product_set", returnStdout: true)
                    env.ENM_ISO_VERSION = "\'" + curl_response.substring(curl_response.lastIndexOf("mediaArtifactVersion")).split('"')[2] + "\'"
                    env.ENM_PRODUCT_SET = "\'" + product_set + "\'"
                    env.ENM_SPRINT = "\'" + env.DROP + "\'"
                    sh 'FILE=config_properties.yml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/OSS/com.ericsson.de.execution/cve-jira-task-generator HEAD "$FILE" | tar -xO "$FILE" >  config_properties.yml'

                    env.ENM_PRODUCT_SET_SED = "\"/^  enm_product_set: /s/:.*\$/: ${ENM_PRODUCT_SET}/\" config_properties.yml"
                    env.ENM_ISO_SED = "\"/^  iso_version: /s/:.*\$/: ${ENM_ISO_VERSION}/\" config_properties.yml"
                    env.ENM_SPRINT_SED = "\"/^  sprint_version: /s/:.*\$/: ${ENM_SPRINT}/\" config_properties.yml"
                    sh "sed -i ${VA_REPORT_SED}"
                    sh "sed -i ${ENM_ISO_SED}"
                    sh "sed -i ${ENM_PRODUCT_SET_SED}"
                    sh "sed -i ${ENM_SPRINT_SED}"

                    sh "sed -i ${CXP_TABLE_SED}"
                    sh "sed -i ${SG_MAPPING_SED}"
                    sh "sed -i ${FOSS_SED}"
                    sh "sed -i ${TEAM_SED}"
                    sh "sed -i ${TEAM_C_SED}"
                    sh "sed -i ${BASE_IMAGE_SED}"
                    if (env.GERRIT_PATCHSET_REVISION) {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va ${GERRIT_PATCHSET_REVISION} "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    } else {
                        sh 'FILE=cENM/ruleset/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
                    }
                    stash includes: "*", name: "ruleset2.0.yaml", allowEmpty: true

                    IMAGES = new ArrayList<String>();
                    BASE_IMAGES = new ArrayList<String>();
                    XRAY_BASE_IMAGES = new ArrayList<String>();
                    XRAY_IMAGES = new ArrayList<String>();
                    IMAGES_KUBE = new ArrayList<String>();
                    IMAGES_KUBE_REPORT = new ArrayList<String>();
                    IMAGES_KUBE_FAIL = [:]
                    IMAGES_KUBESEC_REPORT = new ArrayList<String>();
                    IMAGES_KUBESEC_FAIL = [:]

                    xray_to_scan = "xray:\r\n  name: cENM\r\n  version: " + PRODUCT_SET + "\r\n  paths:"
                    xray_skip_scan = "Images"
                    xray_skip_scan_csv = "Images"
                    anchore_missing_report = "Images"
                    trivy_missing_report = "Images"
                    kubeaudit_missing_report = "Images"
                    kubesec_missing_report = "Images"

                    def csarContentsUrl = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-e2e-ci-generic-local/csar-contents/csar-metadata-artifacts-${PRODUCT_SET}.json"
                    def csarContentsObjectRaw = ["curl", "-H", "accept: application/json", "--url", "${csarContentsUrl}"].execute().text
                    def csarContentsObjectList = new JsonSlurper().parseText(csarContentsObjectRaw)
                    //TODO use chakras base image lists (more than one) here https://gerrit-gamma.gic.ericsson.se/gitweb?p=OSS/com.ericsson.oss.containerisation/enm-containerisation-ci-pipeline.git;a=tree;f=jenkins;h=38d2dd91427bdbf4e2c9b7225879ce10235e183c;hb=b01a488423fe513398ca1114db4a8951bd5b525b
                    def baseImageList = [
                            "eric-enm-init-wait",
                            "eric-enm-neo4j-extension-plugin",
                            "eric-enm-securestorage-init-base",
                            "eric-enm-sles-apache2",
                            "eric-enm-sles-eap6",
                            "eric-enm-sles-eap7",
                            "eric-sec-directoryservices-fd",
                            "eric-enm-credm-controller",
                            "eric-enm-models",
                            "eric-enm-monitoring-eap6",
                            "eric-enm-monitoring-eap7",
                            "eric-enm-monitoring-jre",
                            "eric-enm-sles-base-scripting"]
                    baseImageList = []//remove when base images are included
                    csarContentsObjectList.each {
                        if (it.toString().contains("dependent_docker_images=")) {
                            it."dependent_docker_images".each {
                                if (baseImageList.contains(it.name)) {
                                    baseImage = true
                                } else {
                                    baseImage = false
                                }
                                image = it.artifactory + it.name + ":" + it.version
                                image = image.replace('\'', '')

                                xray_image = it.name + ":" + it.version
                                if (it.artifactory.startsWith('armdocker.rnd.ericsson.se/proj-enm/')) {
                                    if (it.name == 'eric-enmsg-remotedesktop') { //exclude OSI image
                                        xray_skip_scan_csv = xray_skip_scan_csv + "\r\n " + image
                                    } else {

                                        if (baseImage) {
                                            XRAY_BASE_IMAGES.add(xray_image)
                                            BASE_IMAGES.add(image)
                                        } else {
                                            XRAY_IMAGES.add(xray_image)
                                            IMAGES.add(image)
                                        }
                                    }

                                } else {
                                    xray_skip_scan_csv = xray_skip_scan_csv + "\r\n " + image
                                }
                            }
                        }

                    }
                    //Use Below for testing with small number of images
                    // IMAGES = new ArrayList<String>();
                    // XRAY_IMAGES = new ArrayList<String>();
                    // XRAY_IMAGES.add("eric-enmsg-cmutilities:1.33.0-41")
                    // IMAGES.add("armdocker.rnd.ericsson.se/proj-enm/eric-enmsg-cmutilities:1.33.0-41")

                    csarContentsObjectList = null
                    csarContentsObjectRaw = null
                    XRAY_IMAGES.each {
                        env.XRAY_IMAGE = it
                        sh 'echo ${XRAY_IMAGE}>>image-file-xray'
                    }

                    IMAGES.each {
                        env.IMAGE = it
                        sh 'echo ${IMAGE}>>image-file'
                    }

                    BASE_IMAGES.each {
                        env.BASE_IMAGE = it
                        sh 'echo ${BASE_IMAGE}>>base-image-file'
                    }

                    XRAY_BASE_IMAGES.each {
                        env.XRAY_IMAGE = it
                        sh 'echo ${XRAY_IMAGE}>>base-image-file-xray'
                    }
                    writeFile file: "Xray_Images_Not_Scanned_csv.txt", text: xray_skip_scan_csv
                    archiveArtifacts artifacts: 'Xray_Images_Not_Scanned_csv.txt', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'image-file', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'base-image-file', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'image-file-xray', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'base-image-file-xray', allowEmptyArchive: true

                }
            }
        }
        stage('Get RPM Info') {
            steps {
                script {
                    sh 'FILE=tools/cloud/misc/RetrieveImageRpmDb.sh && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/OSS/com.ericsson.de.execution/va_pentest_tools HEAD "$FILE" | tar -xO "$FILE" >  RetrieveImageRpmDb.sh'
                    sh 'chmod +x RetrieveImageRpmDb.sh'
                    sh 'mkdir -p rpmdb'
                    rpmDBCount = 0
                    env.TMP_REM = ''
                    IMAGES.each { IMAGE ->
                        rpmDBCount++
                        withEnv(["IMAGE=${IMAGE}"]) {
                            sh './RetrieveImageRpmDb.sh -z -i ${IMAGE} -p ./rpmdb/. 2> /dev/null'
                            env.TMP_REM = env.TMP_REM + ' ' + IMAGE
                        }
                        if (rpmDBCount == 15) { //cleanup every 15 images
                            echo 'cleaning unused images'
                            sh 'docker rmi -f ${TMP_REM}'
                            env.TMP_REM = ''
                            rpmDBCount = 0
                        }
                    }
                }
            }
        }
        stage('Fetch Base Image Reports') {
            parallel {
                stage('Fetch Grype Base Images') {
                    steps {
                        script {
                            echo 'tmp skipped'
                            //fetchGrype(BASE_IMAGES) //Uncomment when base images are included
                        }
                    }
                }
                stage('Fetch Trivy Base Images') {
                    steps {
                        script {
                            echo 'tmp skipped'
                            //fetchTrivy(BASE_IMAGES) //Uncomment when base images are included
                        }
                    }
                }
            }
        }
        stage('CSV Report Base Images') {
            steps {
                script {
                    //Uncomment when base images are included
                    sh "docker pull armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest"
                    // prepareReports('/va_reports/base-image-file')
                    // removeDuplicates()
                    // archiveArtifacts artifacts: 'anchore-reports/anchore-grype_report*', allowEmptyArchive: true
                    // archiveArtifacts artifacts: 'trivy-reports/trivy_report*', allowEmptyArchive: true
                    // archiveArtifacts artifacts: 'xray_report_proj-enm*', allowEmptyArchive: true

                    // grype_csv_report = sh(script: 'ls remove_duplications/anchore-grype_report*', returnStdout: true)
                    // trivy_csv_report = sh(script: 'ls remove_duplications/trivy_report*', returnStdout: true)
                    // xray_csv_report = sh(script: 'ls remove_duplications/xray_report_proj-enm*', returnStdout: true)
                    // env.GRYPE_CSV_REPORT = grype_csv_report
                    // env.TRIVY_CSV_REPORT = trivy_csv_report
                    // env.XRAY_CSV_REPORT = xray_csv_report
                    // archiveArtifacts artifacts: xray_csv_report, allowEmptyArchive: true
                    // generateCombinedReport(true)
                    //Remove below line when base images are included
                    sh 'echo "Image Name","Image Version","Package Name","Package Version","Package Family","Package Paths","CVE-REF","VulnerabilityID","Severity","Fixed Versions","Image Path","Rpm Package","Additional Info","Found in trivy","Found in anchore_grype","Found in xray" > input_tables/base-images-final_report.csv'
                }
            }
        }
        stage('Fetch Reports') {
            parallel {
                stage('Fetch Grype Images') {
                    steps {
                        script {
                            fetchGrype(IMAGES)
                        }
                        writeFile file: "Anchore_Images_Not_Scanned.txt", text: anchore_missing_report
                        archiveArtifacts artifacts: 'Anchore_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
                stage('Fetch Trivy Images') {
                    steps {
                        script {
                            fetchTrivy(IMAGES)
                        }
                        writeFile file: "Trivy_Images_Not_Scanned.txt", text: trivy_missing_report
                        archiveArtifacts artifacts: 'Trivy_Images_Not_Scanned.txt', allowEmptyArchive: true
                    }
                }
            }
        }
        stage('CSV Report images') {
            steps {
                script {
                    sh "docker pull armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest"
                    prepareReports('/va_reports/image-file', '/va_reports/image-file-xray')
                    removeDuplicates()
                    archiveArtifacts artifacts: 'anchore-reports/anchore-grype_report*', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'trivy-reports/trivy_report*', allowEmptyArchive: true
                    archiveArtifacts artifacts: 'xray_report_proj-enm*', allowEmptyArchive: true

                    grype_csv_report = sh(script: 'ls remove_duplications/anchore-grype_report*', returnStdout: true)
                    trivy_csv_report = sh(script: 'ls remove_duplications/trivy_report* || echo false', returnStdout: true)
                    xray_csv_report = sh(script: 'ls remove_duplications/xray_report_proj-enm*', returnStdout: true)
                    env.GRYPE_CSV_REPORT = grype_csv_report
                    env.TRIVY_CSV_REPORT = trivy_csv_report
                    env.XRAY_CSV_REPORT = xray_csv_report
                    archiveArtifacts artifacts: xray_csv_report, allowEmptyArchive: true
                    generateCombinedReport(false)

                }
            }
        }
        stage('Generate Jira CSV') {
            steps {
                script {
                    sh 'mkdir output_tables'
                    sh 'mkdir log'
                    sh 'docker pull armdocker.rnd.ericsson.se/proj-axis_test/cve-jira-task-generator:latest'
                    sh 'docker run --user $(id -u):$(id -g) -v $(pwd)/config_properties.yml:/cve-jira-task-generator/config_properties.yml -v $(pwd)/log:/cve-jira-task-generator/log -v $(pwd)/input_tables:/cve-jira-task-generator/input_tables -v $(pwd)/db_input_tables:/cve-jira-task-generator/db_input_tables -v $(pwd)/output_tables:/cve-jira-task-generator/output_tables --init --rm armdocker.rnd.ericsson.se/proj-axis_test/cve-jira-task-generator:latest python3.9 ./python_script/Main.py'
                    archiveArtifacts artifacts: 'output_tables/*', allowEmptyArchive: true
                }
            }
        }
    }
}


def fetchGrype(ArrayList GRYPE_IMAGES) {
    sh 'rm -rf anchore-reports'
    sh 'mkdir anchore-reports'
    sh 'cp rpmdb/* anchore-reports'
    GRYPE_IMAGES.each { IMAGE ->
        def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
        def IMAGE_VERSION = IMAGE.split(':').last()
        withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/anchore_grype/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-anchore-grype-scan.tar'
            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
            report_found = !report_check.asBoolean()
            if (report_found) {
                sh '''
                    cd anchore-reports
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/anchore_grype/${IMAGE_NAME}-${IMAGE_VERSION}-anchore-grype-scan.tar
                    tar -xvf ${IMAGE_NAME}-${IMAGE_VERSION}-anchore-grype-scan.tar
                '''
            } else {
                anchore_missing_report = anchore_missing_report + "\r\n " + IMAGE
            }
        }
    }
    env.ANCHORE_VERSION = sh(script: 'cat anchore-reports/anchore_metadata.properties |grep anchore_version|awk -F "=" \'{print $2}\'', returnStdout: true)
}

def fetchTrivy(ArrayList TRIVY_IMAGES) {
    sh 'rm -rf trivy-reports'
    sh 'mkdir trivy-reports'
    sh 'cp rpmdb/* trivy-reports'
    TRIVY_IMAGES.each { IMAGE ->
        def IMAGE_NAME = IMAGE.split(':').first().split('/').last()
        def IMAGE_VERSION = IMAGE.split(':').last()
        withEnv(["IMAGE_NAME=${IMAGE_NAME}", "IMAGE_VERSION=${IMAGE_VERSION}"]) {
            def check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/' + IMAGE_NAME + '-' + IMAGE_VERSION + '-trivy_scan.json'
            def report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
            report_found = !report_check.asBoolean()
            if (report_found) {
                sh '''
                    cd trivy-reports
                    curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/${IMAGE_NAME}-${IMAGE_VERSION}-trivy_scan.json
                    mv ${IMAGE_NAME}-${IMAGE_VERSION}-trivy_scan.json ${IMAGE_NAME}_${IMAGE_VERSION}.json
                '''
            } else {
                trivy_missing_report = trivy_missing_report + "\r\n " + IMAGE
            }
        }
    }

    check_command = 'curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/trivy_metadata_' + PRODUCT_SET + '.properties'
    report_check = sh(script: check_command, returnStdout: true, returnStatus: true)
    report_found = !report_check.asBoolean()
    if (report_found) {
        sh '''
            cd trivy-reports
            curl -u ${PDUOSSVA_ARM_TOKEN} -O https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/trivy/trivy_metadata_${PRODUCT_SET}.properties
        '''
        env.TRIVY_VERSION = sh(script: 'cat trivy-reports/trivy_metadata_${PRODUCT_SET}.properties |grep trivy_version|awk -F "=" \'{print $2}\'', returnStdout: true)
    } else {
        env.TRIVY_VERSION = "NA"
    }
}

def prepareReports(String imageFile, String imageFileXray) {
    env.IMAGE_FILE = imageFile
    env.IMAGE_FILE_XRAY = imageFileXray
    sh 'rm -rf xray_report_proj-enm*'
    sh 'rm -rf results'
    sh 'rm -rf xray_metadata.properties'
    sh 'mkdir xray-reports'
    sh 'cp rpmdb/* xray-reports'
    sh 'docker run --user $(id -u):$(id -g) --init --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/trivy/run_trivy.sh -po -rd=/va_reports/trivy-reports -rm=containerized -pn=cENM -pr=${PRODUCT_SET} -if=${IMAGE_FILE}'
    sh 'docker run --user $(id -u):$(id -g) --init --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/anchore-grype/run_anchore-grype.sh -po -rd=/va_reports/anchore-reports -rm=containerized -pn=cENM -pr=${PRODUCT_SET} -if=${IMAGE_FILE}'
    sh 'docker run -e RUN_VA_USER=${RUN_VA_USR} -e RUN_VA_PASSWORD=${RUN_VA_PSW} --user $(id -u):$(id -g) --init --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/xray/run_xray.sh  -pn=proj-enm -pr=${PRODUCT_SET} -if=${IMAGE_FILE_XRAY} -gr'
}

def removeDuplicates() {
    grype_csv_base_image_report = sh(script: 'ls anchore-reports/anchore-grype_report*', returnStdout: true)
    trivy_csv_base_image_report = sh(script: 'ls trivy-reports/trivy_report*', returnStdout: true)
    xray_zip_base_image_report = sh(script: 'ls xray_report_proj-enm*', returnStdout: true)

    env.GRYPE_CSV_REPORT = grype_csv_base_image_report
    env.TRIVY_CSV_REPORT = trivy_csv_base_image_report
    env.XRAY_ZIP_REPORT = xray_zip_base_image_report

    sh 'rm -f xray_20*.log'
    sh 'rm -rf failed'
    sh 'ls -l'
    sh 'unzip ${XRAY_ZIP_REPORT}'
    xray_csv_report = sh(script: 'ls results/final/xray_report_proj-enm*', returnStdout: true)
    env.XRAY_CSV_REPORT = xray_csv_report
    env.XRAY_VERSION = sh(script: 'cat xray_metadata.properties |grep xray_version|awk -F "=" \'{print $2}\'', returnStdout: true)

    //archiveArtifacts artifacts: xray_csv_report, allowEmptyArchive: true
    sh 'rm -rf remove_duplications'
    sh 'mkdir remove_duplications'
    sh 'docker run --user $(id -u):$(id -g) --init --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/merge/remove_duplications_from_cloud_va_tool_report.sh -r=${GRYPE_CSV_REPORT} -o=/va_reports/remove_duplications/'
    sh 'docker run --user $(id -u):$(id -g) --init --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/merge/remove_duplications_from_cloud_va_tool_report.sh -r=${TRIVY_CSV_REPORT} -o=/va_reports/remove_duplications/'
    sh 'docker run --user $(id -u):$(id -g) --init --rm -v /var/run/docker.sock:/var/run/docker.sock -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/merge/remove_duplications_from_cloud_va_tool_report.sh -r=${XRAY_CSV_REPORT} -o=/va_reports/remove_duplications/'
    sh 'ls -lart remove_duplications'
}

def generateCombinedReport(boolean baseImageReport) {
    sh 'rm -rf combined_report'
    sh 'mkdir combined_report'
    sh 'docker run --user $(id -u):$(id -g) --init --rm -v $(pwd):/va_reports armdocker.rnd.ericsson.se/proj-axis_test/va_pentest_tools:latest /cloud/merge/join_tool_reports.sh -pn=cENM -pr=${PRODUCT_SET} -rd=/va_reports/combined_report -tr=${TRIVY_CSV_REPORT} -ar=${GRYPE_CSV_REPORT} -xr=${XRAY_CSV_REPORT} -tv=${TRIVY_VERSION} -av=${ANCHORE_VERSION} -xv=${XRAY_VERSION}'
    env.REPORT = sh(script: 'ls combined_report/final_report_cENM_*', returnStdout: true)
    if (baseImageReport) {
        sh 'cp ${REPORT} ./input_tables/base-images-final_report.csv'
        archiveArtifacts artifacts: 'input_tables/base-images-final_report.csv', allowEmptyArchive: true
    } else {
        sh 'cp ${REPORT} ./input_tables/final_report.csv'
        sh 'cp ${REPORT} ./input_tables/final_report_enhanced.csv'
        archiveArtifacts artifacts: 'input_tables/final_report.csv', allowEmptyArchive: true
        archiveArtifacts artifacts: 'input_tables/final_report_enhanced.csv', allowEmptyArchive: true
    }
}
