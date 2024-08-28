@Library('ci-cn-pipeline-lib') _
env.bob = new BobCommand()
    .needDockerSocket(true)
    .toString()

pipeline {
    agent {
        node {
            label 'Cloud-Native-Pointfix'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 15, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    stages {
        stage('Checkout OSS VA Git Repository') {
            steps {
                script {
                    git changelog: true, poll: false, url: '${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.va/oss_va'
                }
            }
        }
        stage('Init') {
            steps {
                script {
                    env.CSAR_REPO_URI =  "https://arm902-eiffel004.athtem.eei.ericsson.se:8443/nexus/content/repositories/releases//cENM/csar/enm-installation-package/" + CSAR_PACKAGE_VERSION + "/enm-installation-package-" + CSAR_PACKAGE_VERSION + ".csar"
                    println( "CSAR_REPO_URI + the CSAR Version --> "+CSAR_REPO_URI)

                    echo 'Inject ADP CBO Age tool credentials file'
                    //withCredentials([file(credentialsId: "test_cbos_repo_credentials.yml" , variable: 'repocredentials')]) {
                    withCredentials([file(credentialsId: "cbos_repo_credentials" , variable: 'repocredentials')]) {
                        writeFile file: 'credentials.yaml', text: readFile(repocredentials)
                    }
                    sh '''
                        cp cENM/ruleset/ruleset2.0.yaml .
                        cp cENM/cbos/jsonToCSVConverter.py .
                    '''
                }
            }
        }
        stage('ADP CBO Age tool scanning - CSAR') {
            when { expression{env.CSAR_REPO_URI != ''} }
            steps {
                script {
                    sh """
                        set +x
                        mkdir -p .bob
                        echo Downloading CSAR to be scanned:

                        time wget  ${CSAR_REPO_URI} --progress=bar:noscroll -nv
                        ls *.csar > .bob/var.csar-to-scan
                        ${bob} cbos-age-tool-scan
                    """
                }
            }
        }
        stage('Consolidate reports into Large CSV') {
            steps{
                script{
                    sh """
                        python jsonToCSVConverter.py
                        echo Converting JsON files to Large CSV
                    """
                    archiveArtifacts 'sorted_by_cbOsAge.csv'
                    archiveArtifacts'sorted_by_cbOsAge.html'
                }
            }
        }
        stage('Get CBO Release Data') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'cenmbuild_ARM_token', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        sh """
                            curl -s -u ${USERNAME}:${PASSWORD} -X POST https://arm.epk.ericsson.se/artifactory/api/search/aql -H "content-type: text/plain" -d 'items.find({ "repo": {"\$eq":"docker-v2-global-local"}, "path": {"\$match" : "proj-ldc/common_base_os_release/*"}, "created": {"\$gt" : "2021-08-24"}}).sort({"\$desc": ["created"]})' > release.json
                        """
                    }
                }
            }
        }
        stage('Create Dashboard') {
            steps {
                script {
                    sh '''
                        for chart in eric-enm-bro-integration eric-enm-infra-integration eric-enm-monitoring-integration eric-enm-pre-deploy-integration eric-enm-stateless-integration
                        do
                            rm -rf ${chart}
                            mkdir ${chart}
                            report_json=$(find . -type f -name "*${chart}*.json")

                            cd ${chart}
                            git clone ${GERRIT_MIRROR}/adp-cicd/adp-cbos-dashboard-baseline .

                            install -m 666 /dev/null dashboard.html # overcome permission issues during writing
                            cp ../${report_json} .
                            cp ../release.json .

                            cd ..
                        done
                    '''
                    sh 'docker pull armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest'
                    def chart_list = ['eric-enm-bro-integration', 'eric-enm-infra-integration', 'eric-enm-monitoring-integration', 'eric-enm-pre-deploy-integration', 'eric-enm-stateless-integration']
                    chart_list.each {
                        def buildResult = sh returnStatus: true, script: 'docker run --init --rm --workdir /va_summary -v $(pwd)/' + it + ':/va_summary armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest /usr/bin/python3 template.py'
                    }
                    archiveArtifacts '**/*.html'
                }
            }
        }
    }
    post {
        always {
            script {
                deleteDir()
            }
        }
        failure {
            script {
                echo "Something went wrong...\nPlease check Helm Chart/CSAR URL or Helm Repo credentials."
            }
        }
    }
}