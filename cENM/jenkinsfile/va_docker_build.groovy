import groovy.json.JsonSlurper

pipeline {
    agent {
        node {
            label 'VA'
        }
    }
    options {
        skipDefaultCheckout true
        timestamps()
        timeout(time: 1, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '40', artifactNumToKeepStr: '40'))
    }
    stages {
        stage('Checkout ADP CBOS Dashboard Git Repository') { //ADP Git Repository requires authentication
            steps {
                git branch: 'master',
                    url: '${GERRIT_MIRROR}/adp-cicd/adp-cbos-dashboard-baseline'

                stash includes: "*", name: "requirements.txt", allowEmpty: true
            }
        }
        stage('Checkout OSS VA Git Repository') {
            steps {
                script {
                    if (env.GERRIT_REFSPEC) {
                        checkout changelog: true, \
                        scm: [$class: 'GitSCM', \
                        branches: [[name: '${GERRIT_REFSPEC}']], \
                        gitTool: "${GIT_TOOL}", \
                        doGenerateSubmoduleConfigurations: false, \
                        extensions: [[$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], \
                        submoduleCfg: [], \
                        userRemoteConfigs: [[refspec: '${GERRIT_REFSPEC}', \
                        url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]
                    } else {
                        checkout changelog: true, \
                        scm: [$class: 'GitSCM', \
                        branches: [[name: '${BRANCH}']], \
                        gitTool: "${GIT_TOOL}", \
                        extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CleanBeforeCheckout']], \
                        userRemoteConfigs: [[url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]
                    }
                }
            }
        }
        stage('Build and Push Docker Image') {
            steps {
                unstash "requirements.txt"
                sh '''
                    cp requirements.txt dockerfiles/va_report/
                    sed -e "s/^PyYAML.*/PyYAML==6.0.1/g" -i dockerfiles/va_report/requirements.txt
                    docker build dockerfiles/va_report -t armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest
                    docker push armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest
                    docker rmi $(docker images armdocker.rnd.ericsson.se/proj-axis_test/va-summary:latest --format "{{.ID}}")
                '''
            }
        }
    }
    post{
        always{
            script{
                deleteDir()
            }
        }
    }
}