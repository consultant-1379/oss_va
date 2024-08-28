import groovy.json.JsonSlurper
import java.text.*
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat

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
    }
    environment {
        PDUOSSVA_ARM_TOKEN = credentials('pduossvaArtifactoryAccessTokenSELI')
    }
    stages {
        stage('Run Check') {
            steps {
                script {
                    def latestGreenProductSet = sh(script: 'curl https://ci-portal.seli.wh.rnd.internal.ericsson.com/api/cloudNative/getGreenProductSetVersion/latest/', returnStdout: true)
                    env.RUN_SCAN = 'false'
                    env.PRODUCT_SET = latestGreenProductSet
                    def existingZapReportDetails = sh(script: 'curl -u ${PDUOSSVA_ARM_TOKEN}  https://arm.seli.gic.ericsson.se/artifactory/api/storage/proj-cenm-va-reports-generic-local/zap/cenm-product-set-${PRODUCT_SET}-BaseURL_full.json', returnStdout: true)
                    def lastZapReportDetails = sh(script: 'curl -u ${PDUOSSVA_ARM_TOKEN}  https://arm.seli.gic.ericsson.se/artifactory/api/storage/proj-cenm-va-reports-generic-local/zap/?lastModified', returnStdout: true)
                    if (existingZapReportDetails.contains('createdBy')) {
                        echo "Latest product set already scanned, Do nothing"
                    } else if (existingZapReportDetails.contains('errors')) {
                        echo "Latest product set not scanned, Check last scanned date"
                        def lastScan = readJSON text: lastZapReportDetails
                        def dateCreated = lastScan['lastModified']
                        dateCreated = dateCreated.split('T')[0]

                        def pattern = "yyyy-MM-dd"
                        dateCreated = new SimpleDateFormat(pattern).parse(dateCreated)

                        //dateCreated = Date.parse(pattern, dateCreated)

                        DAY_IN_MS = 1000 * 60 * 60 * 24;
                        cutoffDate = new Date(System.currentTimeMillis() - (3 * DAY_IN_MS)) //set to 3 days
                        if (dateCreated.before(cutoffDate)) {
                            echo 'Last scan too old, run scan'
                            env.RUN_SCAN = 'true'
                        }
                    }
                    if (env.RUN_SCAN == 'true') {
                        def csarContentsUrl = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-e2e-ci-generic-local/csar-contents/csar-metadata-artifacts-${PRODUCT_SET}.json"
                        def csarContentsObjectRaw = ["curl", "-H", "accept: application/json", "--url", "${csarContentsUrl}"].execute().text
                        def csarContentsObjectList = new JsonSlurper().parseText(csarContentsObjectRaw)

                        csarContentsObjectList.each {
                            if (it.toString().contains("csar_details=")) {
                                env.CSAR_PACKAGE_VERSION = it.'csar_details'.'enm_installation_package_version'
                            }
                        }
                        csarContentsObjectList = null
                        csarContentsObjectRaw = null
                    }
                }
            }
        }
        stage('Write Properties File') {
            steps {
                sh '''
                    echo RUN_VA_SCAN=${RUN_SCAN}>va.properties
                    echo PRODUCT_SET=${PRODUCT_SET}>>va.properties
                    echo CSAR_PACKAGE_VERSION=${CSAR_PACKAGE_VERSION}>>va.properties
                    echo K8NAMESPACE=${K8NAMESPACE}>>va.properties
                    echo K8SCLUSTERID=${K8SCLUSTERID}>>va.properties
                    echo BASE_URL=${BASE_URL}>>va.properties
                '''
                archiveArtifacts "va.properties"
            }
        }
    }
}
