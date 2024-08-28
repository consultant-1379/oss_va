#!/usr/bin/env python3

import argparse
import difflib as dl
import requests
import sys
import re
import os

def get_args():
    parser = argparse.ArgumentParser(description='Compares two Summary files, located remotely on Artifactory or locally into the workspace.')
    parser.add_argument("-u", "--user",type=str,help='Artifactory user')
    parser.add_argument("-t", "--token", type=str,help="Artifactory token")
    parser.add_argument("-l", "--url", type=str,help="Artifactory URL")
    parser.add_argument("previous_version", type=str,help="Previous CSAR Version")
    parser.add_argument("current_version", type=str,help="Current CSAR Version")
    parser.add_argument("-d", "--directory", type=str, help="Artifactory Directory")

    return parser.parse_args()

def set_vars(args):
    global current_summary_file_name
    global previous_summary_file_name
    current_csar_version = args.current_version
    previous_csar_version = args.previous_version
    directory = args.directory
    arm_url = args.url

    if directory:
        arm_path = '/' + directory + '/'
    else:
        arm_path = '/'

    try:
        if re.match('[\d/.-]+$', current_csar_version) and re.match('[\d/.-]+$', previous_csar_version):
            previous_summary_file_name = arm_url + arm_path + "summary-" + previous_csar_version + ".csv"
            current_summary_file_name = arm_url + arm_path + "summary-" + current_csar_version + ".csv"
        elif re.match('[\d/.-]+$', current_csar_version) and previous_csar_version == "latest":
            previous_summary_file_name = arm_url + arm_path + "summary-latest.csv"
            current_summary_file_name = arm_url + arm_path + "summary-" + current_csar_version + ".csv"
        elif current_csar_version == "latest":
            print("current_csar_version argument can't be 'latest'")
            sys.exit(1)
        elif re.search('[a-zA-Z]', current_csar_version) and previous_csar_version == "latest":
            previous_summary_file_name = arm_url + arm_path + "summary-latest.csv"
            current_summary_file_name = current_csar_version
        elif re.search('[a-zA-Z]', current_csar_version) and re.search('[a-zA-Z]', previous_csar_version):
            previous_summary_file_name = previous_csar_version
            current_summary_file_name = current_csar_version
        elif re.match('[\d/.-]+$', current_csar_version) and re.search('[a-zA-Z]', previous_csar_version):
            previous_summary_file_name = previous_csar_version
            current_summary_file_name = arm_url + arm_path + "summary-" + current_csar_version + ".csv"
        elif re.match('[\d/.-]+$', previous_csar_version) and re.search('[a-zA-Z]', current_csar_version):
            previous_summary_file_name = arm_url + arm_path + "summary-" + previous_csar_version + ".csv"
            current_summary_file_name = current_csar_version
        else:
            print("Wrong argument/arguments. Please check --help or official Confluence")
            sys.exit(1)
    except TypeError:
        print("Missing or Wrong argument/arguments. Please check --help or official Confluence")
        sys.exit(1)

def generate_diff(arm_user, arm_token):

    if "https://" in previous_summary_file_name:
        r = requests.get(previous_summary_file_name, auth=(arm_user, arm_token), allow_redirects=True)
        sys.exit("GET request to the specified URI -" + previous_summary_file_name + "- doesn't succeed!") if r.status_code != 200 else print("GET request to the specified URI -" + previous_summary_file_name + "- succeed!")
        summary_file1 = open('va_summary/summary_file1.csv', 'wb')
        summary_file1.write(r.content)
        summary_file1.close()
    else:
        os.system('cp ' + previous_summary_file_name + ' va_summary/summary_file1.csv')

    if "https://" in current_summary_file_name:
        r = requests.get(current_summary_file_name, auth=(arm_user, arm_token), allow_redirects=True)
        sys.exit("GET request to the specified URI -" + current_summary_file_name + "- doesn't succeed!") if r.status_code != 200 else print("GET request to the specified URI -" + current_summary_file_name + "- succeed!")
        summary_file2 = open('va_summary/summary_file2.csv', 'wb')
        summary_file2.write(r.content)
        summary_file2.close()
    else:
        os.system('cp ' + current_summary_file_name + ' va_summary/summary_file2.csv')

    with open('va_summary/summary_file1.csv') as file_1, open('va_summary/summary_file2.csv') as file_2:
        added = []
        removed = []
        for line in dl.unified_diff(file_1.readlines(), file_2.readlines()):
            if (line.startswith('+++')):
                next
            elif (line.startswith('+')):
                added.append(line[1:])
            elif (line.startswith('-')):
                removed.append(line[1:])
    added_file = open('va_summary/added.csv', 'w')
    added_file.writelines(["%s" % item for item in added])
    added_file.close()

    removed_file = open('va_summary/removed.csv', 'w')
    removed_file.writelines(["%s" % item for item in removed])
    removed_file.close()

    print("Completed!")

def main():
    args = get_args()
    set_vars(args)
    generate_diff(args.user, args.token)

if __name__ == "__main__":
    try:
        main()
    except ValueError:
        sys.exit(1)