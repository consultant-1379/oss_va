#!/bin/bash

#===================================================
# Get Base Images list for specific cENM Product Set
#===================================================

my_echo() {
  #:;
  echo "$*"
}

get_full_sg_name() {
  local __sg_name="$1"
  local __full_sg_name=$(curl --user ${axisadm_http_gerrit} "https://gerrit-gamma.gic.ericsson.se/a/projects/?r=OSS.*/${__sg_name}" | tail -n +2 | jq -r '.[] | .id')
  if [[ ! -z "$__full_sg_name" ]]; then
    echo "$__full_sg_name" |tr -d '"'
  else
    echo ''
  fi
}

get_commit_from_tag() {
  local __full_sg_name="$1"
  local __sg_version="$2"
  echo $(curl --user ${axisadm_http_gerrit} "https://gerrit-gamma.gic.ericsson.se/a/projects/${__full_sg_name}/tags/${__sg_version}" | tail -n +2 | jq -r '.object')
}

get_dockerfile_content() {
  local __full_sg_name="$1"
  local __commit="$2"
  local __dockerfile_full_path="$3"
  echo "$(curl --user ${axisadm_http_gerrit} "https://gerrit-gamma.gic.ericsson.se/a/projects/${__full_sg_name}/commits/${__commit}/files/${__dockerfile_full_path}/content" |base64 -d |grep -vE '^[ ]*#' |sed $'s/[^[:print:]\t]//g')"
}

get_parent_from_dockerfile () {
  local __dockerfile="$1"
  local __found=$(grep -B10 FROM <<<"$__dockerfile" |grep -oiE "ARG +[a-zA-Z0-9_-]+IMAGE_NAME *= *[a-zA-Z0-9_\-]+|ARG +[a-zA-Z0-9_-]+IMAGE_TAG *= *([0-9.-]+|latest)")
  if [[ ! -z "$__found" ]]; then
    echo "$__found" |cut -d'=' -f 2 |paste -d: - -
  else
    echo ''
  fi
}

get_base_image_info_from_dockerfile () {
  local __dockerfile="$1"
  local __base_image_name_ver=''

  if grep -q 'ARG ERIC_ENM_.*_IMAGE_NAME' <<<"$__dockerfile"; then
      base_image_name=$(grep 'ERIC_ENM_.*_IMAGE_NAME' <<<"$__dockerfile" |head -1 |cut -d '=' -f2)
      base_image_repo=$(grep 'ERIC_ENM_.*_IMAGE_REPO' <<<"$__dockerfile" |head -1 |cut -d '=' -f2)
      base_image_row=$(grep 'ERIC_ENM_.*_IMAGE_TAG' <<<"$__dockerfile"|head -1 )
      [[ "$base_image_row" == *=* ]] && base_image_ver=$(echo "$base_image_row" |cut -d '=' -f2) || base_image_ver="latest"
      __base_image_name_ver="${base_image_repo}/${base_image_name}:${base_image_ver}"
  fi

  if [[ -z "$__base_image_name_ver" ]]; then
    if grep -q 'ARG OSS_.*_VERSION' <<<"$__dockerfile"; then
      base_image_name=$(grep 'ARG OSS_.*_NAME' <<<"$__dockerfile" |head -1 |cut -d '=' -f2)
      base_image_repo=$(grep 'ARG OSS_.*_REPO' <<<"$__dockerfile" |head -1 |cut -d '=' -f2)
      base_image_row=$(grep 'ARG OSS_.*_VERSION' <<<"$__dockerfile"|head -1 )
      [[ "$base_image_row" == *=* ]] && base_image_ver=$(echo "$base_image_row" |cut -d '=' -f2) || base_image_ver="latest"
      __base_image_name_ver="${base_image_repo}/${base_image_name}:${base_image_ver}"
    fi
  fi

  if [[ -z "$__base_image_name_ver" ]]; then
    if grep -q 'ARG .*_NAME' <<<"$__dockerfile"; then
      base_image_name=$(grep 'ARG .*_NAME' <<<"$__dockerfile" |head -1 |cut -d '=' -f2)
      base_image_repo=$(grep 'ARG .*_REPO' <<<"$__dockerfile" |head -1 |cut -d '=' -f2)
      base_image_row=$(grep 'ARG .*_TAG' <<<"$__dockerfile" |head -1)
      [[ "$base_image_row" == *=* ]] && base_image_ver=$(echo "$base_image_row" |cut -d '=' -f2) || base_image_ver="latest"
      __base_image_name_ver="${base_image_repo}/${base_image_name}:${base_image_ver}"
    fi
  fi

  if [[ -z "$__base_image_name_ver" ]]; then
    __base_image_name_ver=$(grep 'FROM ' <<<"$__dockerfile" |head -1 |cut  -d ' ' -f2 |cut  -d '=' -f2)
  fi

  echo "$__base_image_name_ver"
}

#
# MAIN
#

if [[ ($# -eq 0) || ($# -gt 2 ) ]]; then echo "Usage: $0 <cENM Product Set x.y.z> [ SG <name:version> ]"; exit 1; fi

cenm_ps=$1

if [[ ! -z "$2" ]]; then
  all_sg_and_versions=( "$2" )
else
  all_sg_and_versions=( $(curl -Ss https://arm.seli.gic.ericsson.se/artifactory/proj-enm-e2e-ci-generic-local/csar-contents/csar-metadata-artifacts-${cenm_ps}.json |jq -r '.[] |select(.dependent_docker_images) |.dependent_docker_images[]|.name, .version'  |paste -d ":" - -) )
fi

base_images_list=()
for sg in "${all_sg_and_versions[@]}"; do
  IFS=':' read -r -a array <<< $sg
  sg_name="${array[0]}"
  sg_version="${array[1]}"
  unset IFS

  echo ======
  echo "sg = $sg"
  full_sg_name=''
  if [[ "$sg_name" == *-httpd ]]; then
    my_echo "sidecar httpd image found"
    full_sg_name=$(get_full_sg_name "${sg_name%-httpd}")
  else
    full_sg_name=$(get_full_sg_name "$sg_name")
  fi

  if [[ ! -z "$full_sg_name" ]]; then
    my_echo "full sg name: $full_sg_name"
    commit=$(get_commit_from_tag "$full_sg_name" "$sg_version")

    if [[ ! -z "$commit" ]]; then
      my_echo commit="$commit"
      if [[ "$sg_name" == "eric-enm-credm-controller" ]]; then
        dockerfile_path="eric-enm-credm-controller-base%2FDockerfile"
      elif [[ "$sg_name" == "eric-enm-modeldeployservice" ]]; then
        dockerfile_path="docker%2FDockerfile"
      elif [[ "$sg_name" == "eric-pm-alert-manager" ]]; then
        dockerfile_path="docker%2Feric-pm-alert-manager%2FDockerfile"
      elif [[ "$sg_name" == "eric-oss-ingress-controller-nx" ]]; then
        dockerfile_path="docker%2Fsles-nginx-ingress-controller%2FDockerfile"
      elif [[ "$sg_name" == *-httpd ]]; then
        dockerfile_path="${sg_name}%2FDockerfile"
      else
        dockerfile_path="Dockerfile"
      fi

      my_echo dockerfile_path: "$dockerfile_path"
      dockerfile=$(get_dockerfile_content "$full_sg_name" "$commit" "$dockerfile_path")
      if [[ ! -z "$dockerfile" ]]; then
        base_image_name_ver=$(get_base_image_info_from_dockerfile "$dockerfile")
        echo "base image: " "$base_image_name_ver"
        base_images_list+=("$base_image_name_ver")
      else
        my_echo "Dockerfile not found"
      fi
    else
      my_echo "commit not found"
    fi
  else
    my_echo "full sg name not found"
  fi
done

my_echo "${base_images_list[@]}" | tr ' ' '\n' | sort -u | grep "^armdocker.rnd.ericsson.se" | grep ":" | grep -E "eric-enm-sles-base-scripting|eric-enm-sles-apache2|eric-enm-sles-base|eric-enm-sles-eap7" > all_base_images.txt
curl -u ${pduossva_accesstoken_armseli} -X PUT -T all_base_images.txt https://arm.seli.gic.ericsson.se/artifactory/proj-cenm-va-reports-generic-local/base_images/base_images_list_${cenm_ps}.txt
