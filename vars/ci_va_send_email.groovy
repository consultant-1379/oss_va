#!/usr/bin/env groovy

def call(String jobStatus) {
    mail to: 'PDLAZELLES@pdl.internal.ericsson.com',
    from: "enmadm100@lmera.ericsson.se",
    subject: "${jobStatus} VA Scan: ${currentBuild.fullDisplayName}",
    body: "${jobStatus} job run: ${env.BUILD_URL}"
}
