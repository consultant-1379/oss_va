#!/usr/bin/env groovy

def call(String vaTool, Map IMAGES_FAIL) {
    // Groovy Map -> key: va tool name, value: report file name
    // to be updated in case of new va tools
    def tool_list = [anchore: "anchore-scan.tar", trivy: "trivy_scan.json", kubesec: "kubesec-reports.tar", kubeaudit: "kubeaudit-reports.tar", anchore_grype: "anchore-grype-scan.tar", ciscat: "ciscat-scan.tar", nmap: "nmap-scan.tar", tenable: "tenable.tar", defensics: "report.xml", zap: "BaseURL_full.json", kubehunter: "reports.tar", kubebench: "kubebench_results.tar"]

    // Bind credential to variable to push reports to Artifactory SELI
    withCredentials([usernameColonPassword(credentialsId: 'pduossvaArtifactoryAccessTokenSELI', variable: 'PDUOSSVA_ARM_TOKEN')]) {
        if (tool_list[vaTool]) {
            def REPORT_NAME = tool_list[vaTool]

                try {
                    if (vaTool == "kubesec") {
                        sh "cd reports/${IMAGE_NAME}/templates && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../../../"
                    } else if (vaTool == "kubeaudit") {
                        sh "cd reports/arm_charts/${IMAGE_NAME}/templates && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../../../../"
                    } else if (vaTool == "anchore") {
                        sh "cd anchore-reports && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../"
                    } else if (vaTool == "anchore_grype") {
                        sh "cd anchore-grype-reports && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../"
                    } else if (vaTool == "nmap") {
                        sh "cd nmap_reports/nmap_report && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../../"
                    } else if (vaTool == "tenable") {
                        sh "cd nessus_reports/Tenable && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../../"
                    } else if (vaTool == "kubehunter") {
                        sh "cd reports && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../"
                    } else if (vaTool == "kubebench") {
                        sh "cd reports && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../"
                    } else if (vaTool == "ciscat") {
                        sh "cd cis_cat_assessor/report_dir && tar -cvf ${REPORT_NAME} * && mv ${REPORT_NAME} ../../"
                    }
                    // push report file to Artifactory
                    sh "curl -u ${PDUOSSVA_ARM_TOKEN} -X PUT -T ${REPORT_NAME} https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/${vaTool}/${IMAGE_NAME}-${IMAGE_VERSION}-${REPORT_NAME}"
                } catch (Exception e) {
                    IMAGES_FAIL[IMAGE_NAME] = "NoReport"
                    println("Report Not Found, Image: " + IMAGE_NAME + " Error :" + e)
                }
        } else {
            currentBuild.result = "ABORTED"
            error("ABORTING BUILD, ${vaTool} IS NOT A VALID VA TOOL.\nValid Tool list ${tool_list}")
        }
    }
}
