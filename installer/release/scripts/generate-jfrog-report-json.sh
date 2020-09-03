#!/bin/bash
CF_VERSION=$1

REPORT_ID=$(awk 'NF { gsub(/\./, "-", $1); print "/"$1"/"$2"/*"}' | jq --raw-input --slurp "{
  \"name\": \"cloudflow-infra-$CF_VERSION\",
  \"resources\": {
    \"repositories\": [
      {
        \"name\": \"docker-local\",
        \"include_path_patterns\": split(\"\n\") | map(select(. != \"\"))
      }
    ]
  }
}" | curl -s -H "Content-Type: application/json" \
    -XPOST -d @- \
    --user ${JFROG_USER}:${JFROG_API_TOKEN} \
    https://lightbendcloudflow.jfrog.io/xray/api/v1/reports/vulnerabilities | jq .report_id)


echo -n "Running report 'cloudflow-infra-$CF_VERSION' ($REPORT_ID)."
REPORT_STATUS=$(curl -s --user ${JFROG_USER}:${JFROG_API_TOKEN} \
    https://lightbendcloudflow.jfrog.io/xray/api/v1/reports/${REPORT_ID} | jq .status)

while [ "$REPORT_STATUS" == "pending" ] || [ "$REPORT_STATUS" == "running" ]; do
    echo -n ".";
    REPORT_STATUS=$(curl -s --user ${JFROG_USER}:${JFROG_API_TOKEN} \
        https://lightbendcloudflow.jfrog.io/xray/api/v1/reports/${REPORT_ID} | jq .status);
    sleep 1;
done
echo -e "\nReport completed. Downloading pdf"
curl --user ${JFROG_USER}:${JFROG_API_TOKEN} \
    --output cloudflow-infra-$CF_VERSION-vuln-report.zip \
    "https://lightbendcloudflow.jfrog.io/xray/api/v1/reports/export/${REPORT_ID}?file_name=cloudflow-infra-$CF_VERSION-vuln-report&format=pdf"

unzip cloudflow-infra-$CF_VERSION-vuln-report.zip
rm cloudflow-infra-$CF_VERSION-vuln-report.zip