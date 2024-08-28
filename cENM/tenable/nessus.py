#!/bin/python

"""
Connect and handle scans in Nessus 6 using the new REST API.
"""
import argparse
import csv
import json
import os
import shutil
import sys
import requests

import six
import time
import yaml

from requests.packages.urllib3.exceptions import InsecureRequestWarning
from six.moves.configparser import ConfigParser

requests.packages.urllib3.disable_warnings(InsecureRequestWarning)

_verify = False
arm_token = ''
scan_server_url = ''
arm_url = ''
MASK_REPLACE = '******'

ARM_UPLOAD_EXPECTED_RETURN_CODE = 201
ARM_EXIST_EXPECTED_RETURN_CODE = 200
MAX_SCAN_TIME = 43200
INACTIVITY_TIMEOUT = 43200

def build_url(server_url, resource):
    """
    Build URL for server connection.
    """
    return '{0}{1}'.format(server_url, resource)


def connect(method, resource, data=None, server=None, files=None):
    """
    Send a request

    Send a request to Nessus based on the specified data. If the session token
    is available add it to the request. Specify the content type as JSON and
    convert the data to JSON format.
    """
    if server == 'ARM':
        headers = {'X-JFrog-Art-Api': '{0}'.format(arm_token),
                   'Content-type': 'text/html'}
        server_url = arm_url
    elif scan['scan_name'] == 'satellite':
        headers = {'X-ApiKeys': 'accessKey={0}; secretKey={1}'.format(scan['accessKey'], scan['secretKey']),
                   'content-type': 'application/json'}
        server_url = scan_server_url
    else:
        headers = {'x-apikey': 'accesskey={0}; secretkey={1}'.format(scan['accessKey'], scan['secretKey']),
                   'content-type': 'application/json'}
        server_url = scan_server_url
    if method.upper() != 'GET' and server != 'ARM':

        # json.dumps on a None data will convert it to null. This will result in
        # the request body containing null value and not being empty.
        if data is not None:
            data = json.dumps(data)

    if method == 'POST':
        result = requests.post(build_url(server_url, resource), data=data, headers=headers, verify=_verify)
    elif method == 'PUT':
        result = requests.put(build_url(server_url, resource), data=data, headers=headers, verify=_verify, files=files)
    elif method == 'DELETE':
        result = requests.delete(build_url(server_url, resource), data=data, headers=headers, verify=_verify)
    else:
        result = requests.get(build_url(server_url, resource), params=data, headers=headers, verify=_verify)

    # Exit if there is an error.
    if result.status_code != requests.codes.ok:
        print('Got unexpected Status-Code: {0}'.format(result.status_code))
        error_info = result.json()
        try:
            result.raise_for_status()
        except requests.exceptions.HTTPError as e:
            print('Reason: {0} '.format(e))

        # Show also JSON errors if present

        print('Got error in JSON: {0}'.format(error_info['error_msg']))
        sys.exit()

    # Return none when we are deleting, otherwise non-existent JSON will be attempted to be parsed and an exception will be thrown.
    if method == 'DELETE':
        return

    # When downloading a scan we need the raw contents not the JSON data.
    if 'download' in resource:
        return result.content
    else:
        return result.json()


def get_policy():
    """
    Get scan policies

    Get all of the scan policies but return only the title, id and the uuid (if exist)
    of each policy.
    """

    if scan['scan_name'] == 'satellite':
        data = connect('GET', '/policies')
        policies = dict((policy['name'], [policy['id'], policy['template_uuid']]) for policy in data['policies'])
        print(' Available Policies: {}'.format(policies))
        try:
            policy_id = policies[scan['policy']][0]
            policy_template_uuid = policies[scan['policy']][1]
        except KeyError:
            print('\n Error:\n Policy {0} missing on server {1}, select one of:\n'.format(scan['policy'], scan['server']))
            print('\n'.join(policies))
            exit(1)
        print(' policy_template_uuid: {}'.format(policy_template_uuid))
        print(' policy_id: {}'.format(policy_id))
        return (policy_id, policy_template_uuid)
    else:
        data = connect('GET', '/rest/policy')
        policies = dict((policy['name'], [policy['id']]) for policy in data['response']['usable'])
        print(' Available Policies: {}'.format(policies))
        try:
            policy_id = policies[scan['policy']][0]
        except KeyError:
            print('\n Error:\n Policy {0} missing on server {1}, select one of:\n'.format(scan['policy'], scan['server']))
            print('\n'.join(policies))
            exit(1)
        print(' Policy Id: {}'.format(policy_id))
        return (policy_id, None)


def get_history_ids(sid):
    """
    Get history ids

    Create a dictionary of scan uuids and history ids so we can lookup the
    history id by uuid.
    """
    data = connect('GET', '/scans/{0}'.format(sid))

    return dict((history['uuid'], history['history_id']) for history in data['history'])


def get_scan_history(sid, hid):
    """
    Scan history details

    Get the details of a particular run of a scan.
    """
    params = {'history_id': hid}
    data = connect('GET', '/scans/{0}'.format(sid), params)

    return data['info']


def get_desc_data(scaninfoversion, productnumber, productname, scantype):
    """
    Get description details
    https://ericsson.sharepoint.com/sites/Security/SitePages/Scan-details-JSON-data.aspx
    """
    desc_data = '{{\
    \n\t"scaninfoversion": "{0}", \
    \n\t"productnumber": "{1}", \
    \n\t"productname": "{2}", \
    \n\t"type": "{3}" \
    \n}}'.format(scaninfoversion, productnumber, productname, scantype)

    return desc_data


def add(name, desc, targets, ptid, pid):
    """
    Add a new scan

    Create a new scan using the policy_id, name, description and targets. The
    scan will be created in the default folder for the user. Return the id of
    the newly created scan.
    """

    if scan['scan_name'] == 'satellite':
        scan_data = {
            'uuid': ptid,
            'settings': {
                'name': name,
                'description': desc,
                'enabled': 'true',
                'launch': 'ON_DEMAND',
                'policy_id': pid,
                'text_targets': targets
            }
        }
        print(' Scan Data: {}'.format(scan_data))
        data = connect('POST', '/scans', data=scan_data)
        return data['scan']
    else:
        #reports = list(map(str.strip, scan['reports'].split(',')))
        scan_data = {
            'name': name,
            'type': 'policy',
            'description': desc,
            'repository': {
                'id': 141
            },
            'reports': [
                {
                    'id': 20,
                    'reportSource': 'individual'
                },
                {
                    'id': 21,
                    'reportSource': 'individual'
                },
            ],
            'ipList': targets,
            'policy': {
                'id': pid
            },
            'maxScanTime': MAX_SCAN_TIME,
            'inactivityTimeout': INACTIVITY_TIMEOUT
        }
        if int(scan['scanzone']) > 0:
            scan_data['zone'] = { 'id': scan['scanzone'] }
        print(' Scan Data: {}'.format(scan_data))
        data = connect('POST', '/rest/scan', data=scan_data)
        return data['response']


def update(scan_id, name, desc, targets, pid=None):
    """
    Update a scan

    Update the name, description, targets, or policy of the specified scan. If
    the name and description are not set, then the policy name and description
    will be set to None after the update. In addition the targets value must
    be set or you will get an "Invalid 'targets' field" error.
    """

    scan_data = {}
    scan_data['settings'] = {}
    scan_data['settings']['name'] = name
    scan_data['settings']['desc'] = desc
    scan_data['settings']['text_targets'] = targets

    if pid is not None:
        scan_data['uuid'] = pid

    data = connect('PUT', '/scans/{0}'.format(scan_id), data=scan_data)

    return data


def launch(sid):
    """
    Launch a scan

    Launch the scan specified by the scan id. Return the uuid or scan result id.
    """

    if scan['scan_name'] == 'satellite':
        data = connect('POST', '/scans/{0}/launch'.format(sid))

        return data['scan_uuid']
    else:
        data = connect('POST', '/rest/scan/{0}/launch'.format(sid))
        return data['response']['scanResult']['id']


def status(sid, hid=None):
    """
    Check the status of a scan run

    Get the historical information for the particular scan and history id. Return
    the status if available. If not return unknown.
    """
    if scan['scan_name'] == 'satellite':
        scan_history = get_scan_history(sid, hid)
        return scan_history['status']
    else:
        data = connect('GET', '/rest/scanResult/{0}'.format(sid))

        return data['response']['status']


def export_status(sid, fid):
    """
    Check export status

    Check to see if the export is ready for download.
    """

    data = connect('GET', '/scans/{0}/export/{1}/status'.format(sid, fid))

    return data['status'] == 'ready'


def export(sid, hid):
    """
    Make an export request

    Request an export of the scan results for the specified scan and
    historical run. In this case the format is hard coded as nessus but the
    format can be any one of nessus, html, pdf, csv, or db. Once the request
    is made, we have to wait for the export to be ready.
    """

    data = {'history_id': hid,
            'format': 'html',
            'chapters': 'vuln_hosts_summary;vuln_by_host;compliance_exec;remediations;vuln_by_plugin;compliance'}

    data = connect('POST', '/scans/{0}/export'.format(sid), data=data)

    fid = data['file']

    # TODO: add timeout. Duration should be configurable via config file.
    while export_status(sid, fid) is False:
        time.sleep(5)

    return fid


def get_report_results(srid, scan_start_time=None):
    """
    Get the report results

    Get the report results for the scan specified by scan result id. The report description contains
    'Scan Result ID: <scanResultId>'. All report results are listed to find the reports with matching
    scan result id. Report result id is returned together with type of report, e.g pdf, csv.
    """

    print('Scan Result ID: {}'.format(srid))
    csv_ready = False
    pdf_ready = False
    report_results = {}
    timeout = 10*60 # 10 minutes
    elapsed_time = 0
    search_data = {
        'startTime': scan_start_time,
        'filter': 'usable',
        'fields': 'id,name,type,description,status'
    }
    while elapsed_time < timeout:
        data = connect('GET', '/rest/report', data=search_data)

        report_ids = dict((report['id'], [report['description'], report['type'], report['status']]) for report in
                          data['response']['usable'])

        for key, value in report_ids.items():
            status = str(value[2])
            if status == 'Completed':
                # If the report result is completed check if the report is
                # the report for the specific scan, compare scan result ids.
                if srid in str(value[0]):
                    file_type = value[1]
                    if file_type == 'pdf':
                        pdf_ready = True
                    elif file_type == 'csv':
                        csv_ready = True
                    report_results[file_type] = key
            if pdf_ready and csv_ready:
                return report_results
        elapsed_time += 5
        time.sleep(5)

    return report_results


def download(sid, fid, targetDir, file_name):
    """
    Download the scan results

    Download the scan results stored in the export file specified by file id for
    the scan specified by scan id.
    """

    data = connect('GET', '/scans/{0}/export/{1}/download'.format(sid, fid))

    html_file_name = '{0}/{1}'.format(targetDir, file_name)

    print('\n Saving scan results locally to: {0}'.format(html_file_name))

    output = open(html_file_name, 'w')
    output.write(data)
    output.close()


def download_report(rrid, target_dir, file_name):
    """
    Download the scan results

    Download the scan results stored in the report file specified by
    the report result id.
    """

    data = connect('POST', '/rest/report/{0}/download'.format(rrid))

    target_file_name = '{0}/{1}'.format(target_dir, file_name)

    print(' Saving scan results locally to: {}'.format(target_file_name))

    output = open(target_file_name, 'wb')
    output.write(data)
    output.close()
    if file_name.endswith('csv'):
        return validate_csv(target_file_name)
    return True
def validate_csv(file_name):
    """
    Validates if the CSV content is correct
    Right now, this checks only if the CSV has non-empty content.
    """
    with open(file_name) as file:
        reader = csv.reader(file)
        lines = len(list(reader))
        if lines > 1:
            return True
    return False
def delete(sid, srid=None, rr=None):
    """
    Delete a scan result

    If satellite, it deletes a scan and all of its associated history. The scan
    is not moved to the trash folder, it is deleted.

    If central server, it deletes a scan result (activeScan, scanResult and
    reportResults. The scan result is not moved to the trash folder, it is deleted.
    """
    if scan['scan_name'] == 'satellite':
        connect('DELETE', '/scans/{0}'.format(sid))
    else:
        if config.getboolean('cleanup', 'active_scan'):
            connect('DELETE', '/rest/scan/{0}'.format(sid))

        if config.getboolean('cleanup', 'scan_result'):
            connect('DELETE', '/rest/scanResult/{0}'.format(srid))

        if config.getboolean('cleanup', 'report_results'):
            for type, id in rr.items():
                connect('DELETE', '/rest/report/{0}'.format(id))


def history_delete(sid, hid):
    """
    Delete a historical scan.

    This deletes a particular run of the scan and not the scan itself. the
    scan run is defined by the history id.
    """

    connect('DELETE', '/scans/{0}/history/{1}'.format(sid, hid))


def get_dir(path, scan_dir):
    """
    Return a directory for scan results. If the directory does not yet exist, create it.
    """
    output_dir = '{0}/{1}'.format(path, scan_dir)

    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    return output_dir


def get_files(scan_name, scan_version):
    """
    Get filename for scan results
    """
    output_files = []
    if scan['scan_name'] == 'satellite':
        output_files.append('{0}-{1}.html'.format(scan_name, scan_version))
    else:
        output_files.append('{0}-{1}.pdf'.format(scan_name, scan_version))
        output_files.append('{0}-{1}.csv'.format(scan_name, scan_version))

    return output_files


def get_all_files(scan_name, scan_version):
    """
    Get filename for all scan results
    """
    output_files = []
    output_files.append('{0}-{1}.html'.format(scan_name, scan_version))
    output_files.append('{0}-{1}.pdf'.format(scan_name, scan_version))
    output_files.append('{0}-{1}.csv'.format(scan_name, scan_version))

    return output_files


def get_remote_paths(svc_name, sut_version, src_files, arm_server):
    """
    Define scan result full path in ARM
    """
    dst_repo = 'proj-{}-va-generic-local'.format(svc_name)

    dst_full_paths = []
    for src_file in src_files:
        dst_full_paths.append('{0}/{1}/{2}/{3}'.format(arm_server, dst_repo, sut_version, src_file))

    return dst_full_paths


def get_remote_paths_custom_repo(custom_repo, sut_version, src_files):
    """
    Define scan result full path in ARM for service with custom repo structure.
    """
    dst_full_paths = []
    for src_file in src_files:
        dst_full_paths.append('{0}/{1}/{2}'.format(custom_repo, sut_version, src_file))

    return dst_full_paths


def get_scan_res_files(src_dir, src_files):
    """
    Define scan result file name
    """
    src = []
    for src_file in src_files:
        src.append('{0}/{1}'.format(src_dir, src_file))

    return src


def upload(arm_api_token, scan_res_files_path, dst_full_paths):
    """
    Upload scan results to ARM
    """
    if not scan['skipupload']:
        for i in range(len(scan_res_files_path)):
            scan_res_file_path = scan_res_files_path[i]
            if not os.path.exists(scan_res_file_path):
                continue
            dst_full_path = dst_full_paths[i]

            cmd = 'curl -X PUT -T {0} -H "X-JFrog-Art-Api: {1}" {2} -o /dev/null -s -w "%{{http_code}}" > tmp_file'.format(
                scan_res_file_path, arm_api_token, dst_full_path)
            print('\n\n Upload scan results to ARM: {}'.format(dst_full_path))

            # TODO: get it to work with API instead of curl
            #    resource = "/{0}/{1}/{2}".format(dst_repo, date_val, src_file)
            #    connect("PUT", resource, data=open(src, 'rb') , server="ARM", files=None)

            exec_status, http_code = execute(cmd, arm_api_token)

            if exec_status == 0 and http_code == ARM_UPLOAD_EXPECTED_RETURN_CODE:
                print('\n Scan result upload is completed successfully :)')
            elif http_code >= 500:
                # Retry upload once more after 60 seconds
                print(' > Scan result upload has failed :(')
                print(' > http_code {}'.format(http_code))
                print(' > Retrying once more ...')
                time.sleep(60)
                exec_status, http_code = execute(cmd, arm_api_token)
                if exec_status == 0 and http_code == ARM_UPLOAD_EXPECTED_RETURN_CODE:
                    print('\n Scan result upload is completed successfully :)')
                else:
                    print('\n > Scan result upload has failed again :(')
                    print(' > http_code {}'.format(http_code))
                    return False
            else:
                print(' > Scan result upload has failed :(')
                print(' > http_code {}'.format(http_code))
                return False
    return True
def execute(command, mask_token=None, output_file='tmp_file'):
    """
    Execute the provided command using shell command
    """
    if mask_token:
        print('\n Command : {}'.format(command.replace(mask_token, MASK_REPLACE)))
    else:
        print('\n Command : {}'.format(command))
    exec_status = os.system(command)
    http_code = int(open(output_file, 'r').read())
    os.remove(output_file)
    print('\n > exec_status : {}'.format(exec_status))
    print(' > http_code : {}'.format(http_code))
    return exec_status, http_code


def check_if_already_scanned(arm_api_token, dst_full_paths):
    """
    Check if the scan results are available on ARM
    """
    already_scanned = True
    for dst_full_path in dst_full_paths:
        #output_file = 'tmp_file_' + str(time.time_ns())
        output_file = 'tmp_file_'
        cmd = 'curl -H "X-JFrog-Art-Api: {0}" {1} -o /dev/null -s -w "%{{http_code}}" > {2}'.format(arm_api_token,
                                                                                                     dst_full_path,
                                                                                                     output_file)
        exec_status, http_code = execute(cmd, arm_api_token, output_file)

        if exec_status == 0 and http_code == ARM_EXIST_EXPECTED_RETURN_CODE:
            print('\n Scan result already exists :)')
            print(' {}'.format(dst_full_path))
            print(' SKIP!')
            return True
        else:
            print('\n Scan result does not exist ...')
            already_scanned &= False

    return already_scanned


def do_scan(scan, service):
    """
    Run through a scan and clean up.
    """
    print('\n\n Adding new scan for host ' + service['target'] + ':')
    (policy_id, policy_template_uuid) = get_policy()

    desc_data = get_desc_data(scan['scaninfoversion'], service['productnumber'], service['productname'], scan['type'])

    scan_data = add(service['name'], desc_data, service['target'], policy_template_uuid, policy_id)

    scan_id = scan_data['id']
    scan_completed = False
    valid_report = True
    all_reports = True
    upload_success = False
    print('\n Scan Id: {}'.format(scan_id))

    if scan['scan_name'] == 'satellite':
        print('\n\n Launching new Satellite scan...')
        scan_uuid = launch(scan_id)
        history_ids = get_history_ids(scan_id)
        history_id = history_ids[scan_uuid]
        scan_status = status(scan_id, history_id)
        while scan_status not in ['Completed', 'Partial', 'Error']:
            print('\n Scan still running...')
            time.sleep(60)
            scan_status = status(scan_id, history_id)
        if scan_status != 'Completed':
            print('\n Scan exited without completion. Last status: {0}'.format(scan_status))
        else:
            scan_completed = True
            print('\n\n Exporting the completed scan...')
            print(' > Output Directory : {}'.format(output_dir))
            print(' > Output File : {}'.format(output_files[0]))

            file_id = export(scan_id, history_id)
            download(scan_id, file_id, output_dir, output_files[0])

            upload_success = upload(arm_token['armtoken'], scan_res_files_path, dst_full_paths)

            print('\n\n Deleting the scan.')
            history_delete(scan_id, history_id)
            delete(scan_id)
    else:
        print('\n\n Launching new Tenable.sc scan...No satellite')
        scan_result_id = launch(scan_id)
        scan_start_time = int(time.time())
        scan_status = status(scan_result_id)
        while scan_status not in ['Completed', 'Partial', 'Error']:
            print('\n Scan still running...')
            time.sleep(60)
            scan_status = status(scan_result_id)
        if scan_status != 'Completed':
            print('\n Scan exited without completion. Last status: {0}'.format(scan_status))
        else:
            scan_completed = True
            print('\n\n Exporting the completed scan...')

            report_results = get_report_results(scan_result_id, scan_start_time)
            print(' > reportresults = {}'.format(report_results))
            for output_file in output_files:

                pdf_file = output_file.endswith('pdf') and report_results.get('pdf')
                csv_file = output_file.endswith('csv') and report_results.get('csv')

                file_id = pdf_file or csv_file or None
                if file_id:
                    print('\n > Output Directory : {}'.format(output_dir))
                    print(' > Output File : {}'.format(output_file))

                    valid_report &= download_report(file_id, output_dir, output_file)
                else:
                    all_reports = False
            if valid_report:
                upload_success = upload(arm_token['armtoken'], scan_res_files_path, dst_full_paths)

            print('\n\n Deleting the scan from Tenable.sc...\n')
            delete(scan_id, scan_result_id, report_results)
    if not scan['keepreports'] and upload_success:
        print('\n Deleting the reports from {}...\n'.format(output_dir))
        shutil.rmtree(output_dir, ignore_errors=True)
    if not scan_completed:
        exit(1)
    elif not valid_report:
        exit(2)
    elif not upload_success:
        exit(3)
    elif not all_reports:
        exit(4)
if __name__ == '__main__':
    """
    Parse command line arguments, configuration file and start specified scan.
    """

    parser = argparse.ArgumentParser(
        description='Initiate Nessus scan towards Service Under Test(SUT), fetch result, and upload to ARM')
    parser.add_argument('-t', '--target', help='Target IP (SUT Pod IP)', required=True)
    parser.add_argument('-n', '--name', help='SUT name', required=True)
    parser.add_argument('-v', '--version', help='SUT version', required=True)
    parser.add_argument('-arm', '--artifactory', help='Artfactory Manager Server', required=False)
    parser.add_argument('-at', '--armtoken', help='Artfactory Manager Token', required=False)

    parser.add_argument('-p', '--policy', default='ADP_NESSUS_POLICY', help='Scan Policy, prerequisite on the Tenable.sc')
    parser.add_argument('-c', '--configfile', default='nessus.conf', help='Configuration file')
    #parser.add_argument('-crs', '--customrepostructureconfig', default='nessus-custom-repo-structure.conf', help='Custom repo structure configuration file')
    parser.add_argument('-o', '--outputdir', default='/proj/adpci/results/nessus/reports', help='Output Dirctory for Scanning Results')
    parser.add_argument('-fs', '--forcescan', help='Force re-scanning of already scanned services', action='store_true')
    parser.add_argument('-su', '--skipupload', help='Skip upload of scan reports to Artifactory', action='store_true')
    parser.add_argument('-keep', '--keepreports', help='Skip deleting of the nessus reports in helm workspace', action='store_true')
    parser.add_argument('-siv', '--scaninfoversion', default='1.0', help='Used by the automatic central system to parse the information accordingly. 1.0 is the current version.')
    parser.add_argument('-pnum', '--productnumber', default='ADP123456', help='Number from PRIM structure, e.g. the FGM for commercial offerings.')
    parser.add_argument('-pn', '--productname', default='Application Development Platform', help='The full product name, same as "Functional Designation" in PRIM, e.g. the name that is commonly recognized.')
    parser.add_argument('-st', '--type', default='latest', help='The type of scan, e.g. "latest" if it\'s part of a CICD cycle or regular testing or "customer" if it\'s not to be considered a development scan such as a scan of an older version.')
    parser.add_argument('-sd', '--serverdetails', help='Scan server details', required=True)
    parser.add_argument('-sz','--scanzone', help='Scan zone ID defined in Tenable.sc')
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('-tsc', '--tenablesc', action='store_true')
    group.add_argument('-sat', '--satellite', action='store_true')
    options = parser.parse_args()

    with open(options.serverdetails, 'r') as yamlfile:
        server_details = yaml.safe_load(yamlfile)

    scan_details = server_details.get('tenable-sc') if options.tenablesc else server_details.get('satellite')

    # Parse the configuration file to set everything up
    config = ConfigParser()
    if six.PY2:
        # noinspection PyDeprecation
        config.readfp(open(options.configfile))
        # noinspection PyDeprecation
        #config.readfp(open(options.customrepostructureconfig))
    else:
        config.read_file(open(options.configfile))
        #config.read_file(open(options.customrepostructureconfig))
    scan = {
        'policy': options.policy,
        'configfile': options.configfile,
        'outputdir': options.outputdir,
        'skipscan': not options.forcescan,
        'skipupload': options.skipupload,
        'keepreports': options.keepreports,
        'scaninfoversion': options.scaninfoversion,
        'type': options.type,
        'scan_name': 'tenablesc' if options.tenablesc else 'satellite'
    }
    scan['server'] = config.get(scan['scan_name'], 'server')
    scan['port'] = config.get(scan['scan_name'], 'port')
    scan['scanzone'] = options.scanzone if options.scanzone else config.get(scan['scan_name'], 'scanzone', fallback=0)
    #scan['repository'] = config.get(scan['scan_name'], 'repository')
    #scan['reports'] = config.get(scan['scan_name'], 'reports')
    scan.update(scan_details)
    print('Scan Configuration Details : \n' \
          ' Nessus Server           : {0}\n' \
          ' Nessus Server Port      : {1}\n' \
          ' Scan server version     : {2}\n' \
          ' Policy                  : {3}\n' \
          ' Output Directory        : {4}\n' \
          ' AccessKey               : {5}\n' \
          ' SecretKey               : {6}\n' \
          ' Skip scan               : {7}\n' \
          ' Skip upload             : {8}\n' \
          ' Keep reports            : {9}\n'.format(scan['server'], scan['port'],
                                                    scan['scaninfoversion'], scan['policy'],
                                                    scan['outputdir'], MASK_REPLACE, MASK_REPLACE,
                                                    scan['skipscan'], scan['skipupload'],
                                                    scan['keepreports']))
    service = {'name': options.name, 'version': options.version, 'target': options.target,
               'productnumber': options.productnumber, 'productname': options.productname}

    print('Service Details  : \n' \
          ' Name             : {0}\n' \
          ' Version          : {1}\n' \
          ' Target IP\'s     : {2}\n' \
          ' Product Name     : {3}\n' \
          ' Product Number   : {4}\n'.format(service['name'], service['version'],
                                             service['target'], service['productname'],
                                             service['productnumber']))
    arm = {'server': options.artifactory, 'servicecontext': config.get('arm', 'servicecontext')}
    arm_token = {'armtoken': options.armtoken}

    scan_server_url = 'https://{0}:{1}'.format(scan['server'], scan['port'])
    print('\n Nessus Server URL : {}'.format(scan_server_url))

    arm_url = 'https://{0}/{1}'.format(arm['server'], arm['servicecontext'])

    output_dir = get_dir(scan['outputdir'], service['name'])
    output_files = get_files(service['name'], service['version'])
    all_output_files = get_all_files(service['name'], service['version'])

    scan_res_files_path = get_scan_res_files(output_dir, output_files)

    if config.has_option('custom_repo', service['name']):
        custom_repo = config.get('custom_repo', service['name'])
        print('\n ARM Server : {}'.format(custom_repo))
        dst_full_paths = get_remote_paths_custom_repo(custom_repo, service['version'], output_files)
        all_dst_full_paths = get_remote_paths_custom_repo(custom_repo, service['version'], all_output_files)
    else:
        print('\n ARM Server : {}'.format(arm_url))
        dst_full_paths = get_remote_paths(service['name'], service['version'], output_files, arm_url)
        all_dst_full_paths = get_remote_paths(service['name'], service['version'], all_output_files, arm_url)

    if not (scan['skipscan'] and check_if_already_scanned(arm_token['armtoken'], all_dst_full_paths)):
        do_scan(scan, service)
    else:
        shutil.rmtree(output_dir, ignore_errors=True)