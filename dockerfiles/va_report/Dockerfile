FROM armdocker.rnd.ericsson.se/proj-ldc/common_base_os/sles:6.0.0-18

ARG CBOS_REPO=https://arm.rnd.ki.sw.ericsson.se/artifactory/proj-ldc-repo-rpm-local/common_base_os/sles/6.0.0-18?ssl_verify=no
ARG CBOS_DEV_REPO=https://arm.sero.gic.ericsson.se/artifactory/proj-ldc-repo-rpm-local/adp-dev/adp-build-env/6.0.0-18/

ADD va_report.py /
ADD va_diff.py /
ADD requirements.txt /

RUN zypper addrepo -C -G -f $CBOS_REPO CBOS_REPO \
    && zypper addrepo -C -G -f $CBOS_DEV_REPO CBOS_DEV_REPO \
    && zypper refresh \
    && zypper install -l -y python311 python311-pip git curl wget \
    && pip install pandas requests \
    && pip install -r requirements.txt

RUN ln -s /usr/bin/python3.11 /usr/bin/python3

RUN chmod +x va_report.py
RUN chmod +x va_diff.py
CMD ["/bin/sh"]