
# VA Scripts

## Get Base Images - allSGBaseImages.sh

Get Base Images and their version for a specified Product Set or Service Group Image.
If Service Group Image is not specified, the script checks the full list of images associated to
the passed Product Set version.

**Usage:** $0 **cENM Product Set** e.g 24.09.23 [ SG <name:version> ]

Resulting list of Base Images is then pushed to Artifactory in order to be used by:
<https://fem43s11-eiffel004.eiffel.gic.ericsson.se:8443/jenkins/job/cENM_CSAR_CSV_Report_Base_Images/>

## Get RPMs - rpmVAenrichment.py

Read local Vulnerability_Report_2.0.csv generated by:
armdocker.rnd.ericsson.se/proj-adp-cicd-drop/bob-adp-release-auto:latest

**Bob Rule:**

```text
va-report-csv-generation:
  - description: The generation of the VA report for cENM - .CSV format for Anchore, Trivy, X-Ray
  - task: generate
  description: Generating the VA report
  docker-image: adp-release-auto
  cmd: bash -c 'va-report
    --csv
    --output ./Vulnerability_Report_2.0.csv
    --config ./va_report_config.yaml
    --anchore-reports anchore-reports
    --trivy-reports trivy-reports
    --xray xray_report.json'
```

and output an enriched CSV including RPMs information - Vulnerability_Report_RPM_2.0.csv
It has to be launched inside a Python Virtualenv:

```text
. /home/axisadm/cENM_va/311env/bin/activate
python -u ./rpmVAenrichment.py <input file> <output file>
```

The above script is used in following jobs:

* <https://fem43s11-eiffel004.eiffel.gic.ericsson.se:8443/jenkins/job/cENM_CSAR_CSV_Report/>
* <https://fem43s11-eiffel004.eiffel.gic.ericsson.se:8443/jenkins/job/cENM_CSAR_CSV_Report_Base_Images/>
