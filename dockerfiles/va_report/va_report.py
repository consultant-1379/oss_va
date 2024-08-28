#!/usr/bin/env python3

import argparse
import pandas as pd
import requests
import sys

def get_args():
    parser = argparse.ArgumentParser(description='Generates and stores csv files based on Vulnerability analysis reports.')
    parser.add_argument("version", type=str, help="CSAR Version")
    parser.add_argument("-u", "--user", type=str, help='Artifactory user')
    parser.add_argument("-t", "--token", type=str, help="Artifactory token")
    parser.add_argument("-l", "--url", type=str, help="Artifactory URL")
    parser.add_argument("-d", "--directory", type=str, help="Artifactory Directory")
    parser.add_argument("-lt", "--latest", type=str, help="Store latest VA report. True/False", default="True")
    return parser.parse_args()

def set_vars(args):
    global csar_version
    global summary_file_name
    global summary_file_name_no_tool
    global totals_file_name
    global detailed_file_name

    csar_version = args.version
    summary_file_name = "./va_summary/summary-" + csar_version + ".csv"
    detailed_file_name = "./va_summary/detailed-" + csar_version + ".csv"
    summary_file_name_no_tool = "./va_summary/summary-no-tool-" + csar_version + ".csv"
    totals_file_name = "./va_summary/totals.csv"

def generate_files():

    file1 = open('./va_summary/Vulnerability_Report_2.0.md', 'r', errors='ignore')
    Lines = file1.readlines()

    count = 0
    write_summary = False
    write_detailed = False
    image = ""
    for line in Lines:
        count += 1

        if "### Summary of found vulnerabilities:" in line:
            summary_file = open(summary_file_name, "w")
            summary_file_no_tool = open(summary_file_name_no_tool, "w")
            summary_file.write("Severity,Slogan,Tool\n")
            summary_file_no_tool.write("Severity,Slogan\n")
            write_summary = True

        if "## Results from network port scanning" in line:
            write_summary = False
            summary_file.close()
            summary_file_no_tool.close()
        if "## Details of vulnerabilities and their mitigation are presented below" in line:
            write_detailed = False
            detailed_file.close()

        if "## Results for individual docker images" in line:
            detailed_file = open(detailed_file_name, "w")
            detailed_file.write("Image,Slogan,Severity\n")
            write_detailed = True

        if (write_summary):
            if "### Summary of found vulnerabilities:" in line:
                continue
            if line == '\n':
                continue
            if "| Tag  | Severity | Slogan | Tool | Link |" in line:
                continue
            if "| :--- | :------: | :----: | :--: | :--: |" in line:
                continue

            line_split = line.split('|')
            severity = line_split[2]
            slogan = line_split[3]
            if "," in slogan:
                ######TMP WORKAROUND NEEDS TO BE FIXED~~~
                slogan = slogan.split(',')[0]
            tool = line_split[4].replace(',', '#')
            summary_file.write(severity + "," + slogan + "," + tool + "\n")
            summary_file_no_tool.write(severity + "," + slogan + "\n")
        if (write_detailed):

            if "### Vulnerabilities found in docker image " in line:
                image = line.replace("### Vulnerabilities found in docker image ", "").replace("\n", "")

            if "|[" in line:
                line_split = line.split('|')
                severity_xray = line_split[2]
                severity_anchore = line_split[3]
                severity_trivy = line_split[4]
                slogan = line_split[1]
                slogan = slogan.split("]")[0].replace("[", "")
                if "," in slogan:
                    ######TMP WORKAROUND NEEDS TO BE FIXED~~~
                    slogan = slogan.split(',')[0]
                ######TMP WORKAROUND NEEDS TO BE FIXED~~~
                if severity_xray != "":
                    severity = severity_xray
                if severity_anchore != "":
                    severity = severity_anchore
                if severity_trivy != "":
                    severity = severity_trivy
                if "," in severity:
                    ######TMP WORKAROUND NEEDS TO BE FIXED~~~
                    severity = severity.split(',')[0]
                detailed_file.write(image + "," + slogan + "," + severity + "\n")


def generate_totals():
    df = pd.read_csv(summary_file_name_no_tool)
    df.Severity.unique()
    df.nunique()
    High = Medium = Low = Informational = 0
    try:
        High = df['Severity'].value_counts()['High']
    except:
        print("No value")
    try:
        Medium = df['Severity'].value_counts()['Medium']
    except:
        print("No value")
    try:
        Low = df['Severity'].value_counts()['Low']
    except:
        print("No value")
    try:
        Informational = df['Severity'].value_counts()['Informational']
    except:
        print("No value")

    totals_file = open(totals_file_name, "w")
    totals_file.write("High,Medium,Low,Informational\n")
    totals_file.write(str(High) + "," + str(Medium) + "," + str(Low) + "," + str(Informational) + "\n")
    totals_file.close()

def store_files(arm_url, arm_user, arm_token, file_name, remote_file_name, latest, directory):
    if directory:
        arm_path = '/' + directory + '/'
    else:
        arm_path = '/'
    url_version = arm_url + arm_path + remote_file_name
    remote_file_name_latest = remote_file_name.replace(csar_version, "latest")
    url_latest = arm_url + arm_path + remote_file_name_latest

    with open(file_name, 'rb') as f:
        if latest == "True":
            rl = requests.put(url_latest, auth=(arm_user, arm_token), data=f)
            sys.exit("PUT request to the specified URI -" + url_latest + "- doesn't succeed!") if rl.status_code != 201 else print(rl.json()['downloadUri'])
        f.seek(0, 0)
        rv = requests.put(url_version, auth=(arm_user, arm_token), data=f)
    sys.exit("PUT request to the specified URI -" + url_version + "- doesn't succeed!") if rv.status_code != 201 else print(rv.json()['downloadUri'])

def main():
    args = get_args()
    set_vars(args)
    generate_files()
    generate_totals()

    if args.url and args.user and args.token:
        remote_file_name="summary-" + csar_version + ".csv"
        store_files(args.url, args.user, args.token, summary_file_name, remote_file_name, args.latest, args.directory)
        remote_file_name="detailed-" + csar_version + ".csv"
        store_files(args.url, args.user, args.token, detailed_file_name, remote_file_name, args.latest, args.directory)

if __name__ == "__main__":
    try:
        main()
    except ValueError:
        sys.exit(1)