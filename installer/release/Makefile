SHELL:=/bin/bash

include definitions.mk

all: bootstrap-install-script

bootstrap-install-script: bootstrap-install-script-${version}.sh

bootstrap-install-script-${version}.sh: bootstrap-install-script.sh.template cloudflow-installer.yaml cloudflow.yaml definitions.mk
	# build the final file from the base files
	awk ' \
	/CLOUDFLOW_INSTALLER_YAML=""/ { \
		print "CLOUDFLOW_INSTALLER_YAML=$$(cat <<- END"; \
		while(getline line < "cloudflow-installer.yaml") { print line }; \
		print "END"; \
		print ")"; \
		next \
	} \
	/CLOUDFLOW_YAML=""/ { \
		print "CLOUDFLOW_YAML=$$(cat <<- END"; \
		while(getline line < "cloudflow.yaml") { print line }; \
		print "END"; \
		print ")"; \
		next \
	} \
	/CLOUDFLOW_INSTALLER_IMAGE_TAG=""/ { \
		print "CLOUDFLOW_INSTALLER_IMAGE_TAG=\"${cloudflow_installer_image_tag}\""; \
		next \
	} \
	/CLOUDFLOW_OPERATOR_IMAGE_NAME=""/ { \
		print "CLOUDFLOW_OPERATOR_IMAGE_NAME=\"${cloudflow_operator_image_name}\""; \
		next \
    } \
	/CLOUDFLOW_OPERATOR_IMAGE_TAG=""/ { \
		print "CLOUDFLOW_OPERATOR_IMAGE_TAG=\"${cloudflow_operator_image_tag}\""; \
		next \
	} \
	/CLOUDFLOW_SPARK_OPERATOR_IMAGE_NAME=""/ {\
		print "CLOUDFLOW_SPARK_OPERATOR_IMAGE_NAME=\"${cloudflow_spark_operator_image_name}\""; \
		next \
	}\
	/CLOUDFLOW_SPARK_OPERATOR_IMAGE_TAG=""/ {\
		print "CLOUDFLOW_SPARK_OPERATOR_IMAGE_TAG=\"${cloudflow_spark_operator_image_tag}\""; \
		next \
	}\
	/CLOUDFLOW_FLINK_OPERATOR_IMAGE_NAME=""/ {\
		print "CLOUDFLOW_FLINK_OPERATOR_IMAGE_NAME=\"${cloudflow_flink_operator_image_name}\""; \
		next \
	}\
	/CLOUDFLOW_FLINK_OPERATOR_IMAGE_NAME=""/ {\
		print "CLOUDFLOW_FLINK_OPERATOR_IMAGE_NAME=\"${cloudflow_flink_operator_image_tag}\""; \
		next \
	}\
	{ print } \
	' bootstrap-install-script.sh.template > bootstrap-install-script-${version}.sh
	chmod +x bootstrap-install-script-${version}.sh
