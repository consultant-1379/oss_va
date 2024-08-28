#!/usr/bin/env groovy

def call(String vaTool) {
    // Groovy Map -> key: va tool name, value: report file name
    // to be updated in case of new va tools
    def tool_list = [anchore: "anchore-scan.tar", trivy: "trivy_scan.json", kubesec: "kube-reports.json", kubeaudit: "kube-reports.json", anchore_grype: "anchore-grype-scan.tar", ciscat: "ciscat-scan.tar"]

    // Bind credential to variable to fetch reports from Artifactory SELI
    withCredentials([usernameColonPassword(credentialsId: 'pduossvaArtifactoryAccessTokenSELI', variable: 'PDUOSSVA_ARM_TOKEN')]) {
        if (tool_list[vaTool]) {
            def REPORT_NAME = tool_list[vaTool]
            def check_command = "curl -u ${PDUOSSVA_ARM_TOKEN} --output /dev/null --silent --head --fail https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/${vaTool}/${IMAGE_NAME}-${IMAGE_VERSION}-${REPORT_NAME}"
            def report_check = sh(script: check_command , returnStdout: true, returnStatus: true)
            def generate_report= report_check.asBoolean()
            return generate_report
        } else {
            currentBuild.result = "ABORTED"
            error("ABORTING BUILD, ${vaTool} IS NOT A VALID VA TOOL.\nValid Tool list ${tool_list}")
        }
    }
}
