import json
import csv
import os
import requests
import glob
import sys
import time
import unicodedata

WORKSPACE = os.environ.get('WORKSPACE')
path = WORKSPACE  +  "/reports"


count = 0
headers_names = " intChart, serviceName,	serviceVersion, imageName, imageVersion, cbOsVersion,  cbOsReleaseDate,      cbOsAge\n"
headers = "intChart, serviceName,	serviceVersion, imageName, imageVersion, cbOsVersion,  cbOsReleaseDate,      cbOsAge\n"
sorted_rows = {}
ages_arr = []

enmRef = "report-"

for filename in glob.glob(os.path.join(path, '*.json')):

    startStringPos = filename.index(enmRef) + len(enmRef)
    endStringPos = filename.find('.json')
    int_chart=(filename[startStringPos:endStringPos])
    int_chart = "-".join(int_chart.split("-")[:-3])
    print("INTEGRATION CHART", int_chart)

    with open(os.path.join(os.getcwd(), filename), 'r') as f:  # open in readonly mode
        whole_json = json.load(f)

    report_data = whole_json['microServices']

    for report in report_data:
        mainObject = int_chart  + "," + report["serviceName"].replace(",", "") + "," + report["serviceVersion"].replace(",", "")
        if report["imagesInfo"] == []:
            row = mainObject + ",-,-,-,-,-"
            headers += row + "\n"
        #if report["imagesInfo"] == []:
            #continue
        else:
            for images_info in report["imagesInfo"]:
                row =  mainObject + "," + images_info["imageName"].replace(",", "") + "," + images_info["imageVersion"].replace(",", "") + "," + images_info[
                    "cbOsVersion"].replace(",", "") + "," + images_info["cbOsReleaseDate"].replace(",", "") + "," + str(images_info["cbOsAge"]).replace(",", "")

                headers += row + "\n"
                if images_info["cbOsAge"] in sorted_rows:
                    sorted_rows[images_info["cbOsAge"]] += row + "\n"
                else:
                    sorted_rows[images_info["cbOsAge"]] = row + "\n"
                if images_info["cbOsAge"] not in ages_arr:
                    ages_arr.append(images_info["cbOsAge"])

ages_arr.sort(reverse=True)

sorted_csv = headers_names
for age in ages_arr:
    sorted_csv += sorted_rows[age]

sorted_csv_file = open("sorted_by_cbOsAge.csv", "w")
sorted_csv_file.write(sorted_csv)


time.sleep(20)

print("breakcode")
print(sorted_csv)
fileout = open("sorted_by_cbOsAge.html", "w")
data = sorted_csv.split("\n")

# #109710
# #e39506

html_start_stuff = "<!DOCTYPE html> <html> <style> .orange{color: orange; font-weight: 600;}  .white{color: white; font-weight: 600;}  .darkpink{color: fuchsia; font-weight: 600;} .lightgreen {color: #05F935; font-weight: 600;}  .green {color: green; font-weight: 600; } .lightblue {color: lightblue;} .darkgreen {color: darkgreen; font-weight: 600;} .black {color: black;  font-weight: 600;} .gray {color: gray;  font-weight: 600;}  .red {color: red; font-weight: 600;} </style><head>  <title> Consolidated CBOS Reports</title> </head>"


html_end_stuff = "</html>"
fileout.write(html_start_stuff)

table = "<table>\n"
table2 =  "<table class = 'table2' >\n"
###########################
#create colour legend table

table2 += "<tr class='heading'> <th>Threshold Value </th>"
table2 += "  </tr>\n"



#for column in headings:
table2 += "  <tr class = red >\n"
table2 += "<td class = white >CBOS age > 41 weeks               </td>"

table2 += "  </tr>\n"

table2 += "  <tr class = orange>\n"
table2 += "<td class = white >CBOS age > 31 weeks               </td>"

table2 += "  </tr>\n"

table2 += "  <tr class = lightgreen >\n"
table2 += "<td class = white >CBOS age > 6 weeks                 </td>"

table2 += "  </tr>\n"


table2 += "  <tr class = darkgreen >\n"
table2 += "<td class = white>Latest release CBOS version                </td>"

table2 += "  </tr>\n"


table2 += "  <tr class = darkpink >\n"
table2 += "<td class = white>Public image                </td>"

table2 += "  </tr>\n"



table2 += "  <tr class = gray >\n"
table2 += "<td class = white>No CBOS information (or) CBOS not used                </td>"

table2 += "  </tr>\n"



table2 += "  <tr class = black >\n"
table2 += "<td class = white>Image cannot be downloaded                </td>"

table2 += "  </tr>\n"

table2 += "</table>"



#####################

# Create the table's column headers
header = data[0].split(",")
table += "  <tr class='heading'>\n"
for column in header:
    table += "    <th>{0}</th>\n".format(column.strip())
table += "  </tr>\n"

for line in data[1:]:
    t_row = line.split(",")
    print("~~~~~")
    table += "  <tr>\n"
    if line:
        for column in t_row:
            max_age = t_row[-1][0].encode('utf-8')
            print("-------------> ")
            print("t_row ----> ", t_row)
            try:
                cbos_age = t_row[7].encode('utf-8')
                #if line == data[1]:
                if int(cbos_age) >= 41:
                    table += "    <td class = red>{0}</td>\n".format(column.strip())
                    print("HELLO ------>")
                elif t_row[5] == "No Information":
                    table += "    <td class = gray>{0}</td>\n".format(column.strip())
                elif t_row[5] == "Public image No CBOS Version" :
                    table += "    <td class = darkpink>{0}</td>\n".format(column.strip())
                elif int(cbos_age) >= 31 and int(cbos_age) < 41:
                    table += "    <td class = orange>{0}</td>\n".format(column.strip())
                #elif int(cbos_age) > 6 and line != data[1]:
                elif int(cbos_age) < 31 and int(cbos_age) != 0:
                    table += "    <td class = lightgreen>{0}</td>\n".format(column.strip())
                #elif t_row[4] == "latest":
                    #table += "    <td class = darkgreen>{0}</td>\n".format(column.strip())
                else:
                    table += "    <td class = black>{0}</td>\n".format(column.strip())
            except (ValueError, IndexError) as e:
                print("wrong value in data. Find the reason why.")
        table += "   </tr>\n"
table += "</table>"
table += """
<style>
.heading {
background-color: #5F99B6;
}

title{
  display: inline;
}

.table2 .red {
background-color:red;
}

.table2 .orange {
background-color:orange;
}

.table2 .lightgreen {
background-color:#05F935;
}

.table2 .darkgreen {
background-color:darkgreen;
}

.table2 .darkpink {
background-color:fuchsia;
}

.table2 .gray {
background-color:gray;
}

.table2 .black {
background-color:black;
}


table2 {
  font-family: arial, sans-serif;
  border-collapse: collapse;
  #width: 50%;
  margin:20px
}

table {
  font-family: arial, sans-serif;
  border-collapse: collapse;
  #width: 100%;
  margin:20px
}

td, th {
  border: 1px solid #dddddd;
  text-align: left;
  padding: 8px;
  font-weight: 600;
}

tr:nth-child(even) {
  background-color: #dddddd;
  font-weight: 600;
}
</style>
"""
fileout.writelines(table2)
fileout.writelines(table)

fileout.write(html_end_stuff)

sorted_csv_file.close()
fileout.close()










# import csv
# import os
# import requests
# import glob
# import sys
# import time
# import unicodedata
#
# WORKSPACE = os.environ.get('WORKSPACE')
# # JOB_NAME = os.environ.get('JOB_NAME')
# path = WORKSPACE + "/reports"
#
# print("path ---> ", path)
# print("WORKSPACE -->", WORKSPACE)
# # print("JOB_NAME --> ", JOB_NAME)
#
# count = 0
# headers_names = " intChart, serviceName,	serviceVersion,	imageName, imageVersion, cbOsVersion, cbOsReleaseDate,    cbOsAge,    avgAge,	   maxAge \n"
# headers = "intChart, serviceName,	serviceVersion,	imageName, imageVersion, cbOsVersion, cbOsReleaseDate,    cbOsAge,    avgAge,	   maxAge \n"
# sorted_rows = {}
# ages_arr = []
#
# # path = '/home/lciadm100/jenkins/workspace/cENM-CBOS-CSAR/reports'
#
# enmRef = "enm-"
# # integrationRef = "-integration"
#
#
# for filename in glob.glob(os.path.join(path, '*.json')):
# 
#     startStringPos = filename.index(enmRef) + len(enmRef)
#     endStringPos = filename.find('-integration')
#     int_chart = (filename[startStringPos:endStringPos])
#     print("INTEGRATION CHART", int_chart)
#     with open(os.path.join(os.getcwd(), filename), 'r') as f:  # open in readonly mode
#         whole_json = json.load(f)
#
#     report_data = whole_json['microServices']
#
#     for report in report_data:
#         mainObject = int_chart + "," + report["serviceName"] + "," + report["serviceVersion"]
#         if report["imagesInfo"] == []:
#             continue
#         else:
#             for images_info in report["imagesInfo"]:
#                 row = mainObject + "," + images_info["imageName"] + "," + images_info["imageVersion"] + "," + \
#                       images_info[
#                           "cbOsVersion"] + "," + images_info["cbOsReleaseDate"] + "," + str(images_info["cbOsAge"])
#
#                 row += "," + str(report["avgAge"]) + "," + str(report["maxAge"])
#
#                 headers += row + "\n"
#                 if images_info["cbOsAge"] in sorted_rows:
#                     sorted_rows[images_info["cbOsAge"]] += row + "\n"
#                 else:
#                     sorted_rows[images_info["cbOsAge"]] = row + "\n"
#                 if images_info["cbOsAge"] not in ages_arr:
#                     ages_arr.append(images_info["cbOsAge"])
#
# ages_arr.sort(reverse=True)
#
# sorted_csv = headers_names
# for age in ages_arr:
#     sorted_csv += sorted_rows[age]
#
# sorted_csv_file = open("sorted_by_cbOsAge.csv", "w")
# sorted_csv_file.write(sorted_csv)
#
# time.sleep(20)
#
# print("breakcode")
# print(sorted_csv)
# fileout = open("sorted_by_cbOsAge.html", "w")
# data = sorted_csv.split("\n")
#
# html_start_stuff = "<!DOCTYPE html> <html> <style> .orange{color: orange;} .lightgreen {color: lightgreen;}  .green {color: green;} .lightblue {color: lightblue;} .darkgreen {color: darkgreen;} .black {color: black;} .gray {color: gray;}  .red {color: red;} </style><head> </head>"
# html_end_stuff = "</html>"
# fileout.write(html_start_stuff)
# 
# table = "<table>\n"
# #####################
#
# # Create the table's column headers
# header = data[0].split(",")
# table += "  <tr>\n"
# for column in header:
#     table += "    <th>{0}</th>\n".format(column.strip())
# table += "  </tr>\n"
#
# for line in data[1:]:
#     t_row = line.split(",")
#     print("~~~~~")
#     table += "  <tr>\n"
#     if line:
#         for column in t_row:
#             max_age = t_row[-1][0].encode('utf-8')
#             print("-------------> ")
#             print("t_row ----> ", t_row)
#             try:
#                 cbos_age = t_row[7].encode('utf-8')
#                 # if line == data[1]:
#                 if int(cbos_age) >= 41:
#                     table += "    <td class = red>{0}</td>\n".format(column.strip())
#                     print("HELLO ------>")
#                 elif int(cbos_age) >= 31 and int(cbos_age) < 41:
#                     table += "    <td class = orange>{0}</td>\n".format(column.strip())
#                 # elif int(cbos_age) > 6 and line != data[1]:
#                 elif int(cbos_age) >= 6 and int(cbos_age) < 31:
#                     table += "    <td class = lightgreen>{0}</td>\n".format(column.strip())
#                 elif t_row[4] == "latest":
#                     table += "    <td class = darkgreen>{0}</td>\n".format(column.strip())
#                 elif t_row[5] == "No Information":
#                     table += "    <td class = gray>{0}</td>\n".format(column.strip())
#                 elif t_row[5] == "Public image, No CBOS Version" or t_row[4] == "VERSION":
#                     table += "    <td class = pink>{0}</td>\n".format(column.strip())
#                 elif int(cbos_age) <= 6 and t_row[5] != "No Information":
#                     table += "    <td class = lightblue>{0}</td>\n".format(column.strip())
#                 else:
#                     table += "    <td class = black>{0}</td>\n".format(column.strip())
#             except (ValueError, IndexError) as e:
#                 print("wrong value in data. Find the reason why.")
#         table += "   </tr>\n"
# table += "</table>"
#
# fileout.writelines(table)
# fileout.write(html_end_stuff)
#
# sorted_csv_file.close()
# fileout.close()