# Build the Cloudflow documentation

include definitions.mk

SHELL_DIR := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := ${SHELL_DIR}/..

antora_docker_image     := antora/antora
antora_docker_image_tag := 2.3.1

work_dir := ${ROOT_DIR}/docs/target

staging_dir := ${work_dir}/staging

javascaladoc_dir := ${staging_dir}/docs/current/api

all: build

local-preview: html-author-mode
	@echo "Access the documentation on http://localhost:8000"
	(cd target/staging/; python3 -m http.server)


show:
	echo work dir: ${work_dir}
	echo ROOT_DIR: ${ROOT_DIR}

clean:
	rm -rf ${work_dir}

build: clean html javascaladoc_staged print-site

set-version:
	# get latest tag
	$(eval version=$(shell sh -c "git describe --abbrev=0 | sed 's/v//'"))
	# query bintray for the corresponding artifact version
	$(eval bintray_version=$(shell sh -c "wget -qO - https://api.bintray.com/packages/lightbend/cloudflow-cli/kubectl-cloudflow | jq -r '.versions[] | select( . | startswith(\"${version}.\") )'"))	

html: clean set-version
	yq write docs-source/base-site.yml 'asciidoc.attributes.kubectl-plugin-version' "${bintray_version}" > docs-source/site.yml
	docker run \
		-u $(shell id -u):$(shell id -g) \
		--privileged \
		-v ${ROOT_DIR}:/antora \
		--rm \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		--cache-dir=./.cache/antora \
		--stacktrace \
		docs/docs-source/site.yml
	@echo "Done"

html-author-mode: clean set-version
	yq write shared-content-source/docs/base-antora.yml version "${version}" > shared-content-source/docs/antora.yml
	yq write ../examples/snippets/base-antora.yml version "${version}" > ../examples/snippets/antora.yml

	yq write docs-source/base-author-mode-site.yml 'asciidoc.attributes.cloudflow-version' "${version}" > docs-source/author-mode-site.yml
	yq write -i docs-source/author-mode-site.yml 'asciidoc.attributes.cloudflow-examples-version' "${version}"
	yq write -i docs-source/author-mode-site.yml 'asciidoc.attributes.cloudflow-branch-version' "v${version}"
	yq write -i docs-source/author-mode-site.yml 'asciidoc.attributes.kubectl-plugin-version' "${bintray_version}"

	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${ROOT_DIR}:/antora \
		--rm \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		--cache-dir=./.cache/antora \
		--stacktrace \
		docs/docs-source/author-mode-site.yml
	@echo "Done"

check-links:
	docker run \
		-v ${ROOT_DIR}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t lightbend/antora-doc:0.1.0 \
		--cache-dir=./.cache/antora \
		-c 'find docs/docs-source -name '*.adoc' -print0 | xargs -0 -n1 asciidoc-link-check -p -c docs/docs-source/asciidoc-link-check-config.json'

list-todos: html
	docker run \
		-v ${ROOT_DIR}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		--cache-dir=./.cache/antora \
		-c 'find /antora/docs-source/build/site/cloudflow/${version} -name "*.html" -print0 | xargs -0 grep -iE "TODO|FIXME|REVIEWERS|adoc"'

# Generate the ScalaDoc and the JavaDoc, and put it in ${output}/scaladoc and ${output}/javadoc
javascaladoc: 
	cd ${ROOT_DIR}/core && sbt clean unidoc

javascaladoc_staged: ${javascaladoc_dir} javascaladoc
	cp -r ${ROOT_DIR}/core/target/scala-2.12/unidoc ${javascaladoc_dir}/scaladoc
	cp -r ${ROOT_DIR}/core/target/javaunidoc ${javascaladoc_dir}/javadoc

${work_dir}: 
	mkdir -p ${work_dir}

${staging_dir}:
	mkdir -p ${staging_dir}

${javascaladoc_dir}: 	
	mkdir -p ${javascaladoc_dir}/scaladoc
	mkdir -p ${javascaladoc_dir}/javadoc

print-site:
	# The result directory with the contents of this build:
	@echo "${staging_dir}"

release: set-version
	# Creating a new `current` version
	# * In `docs/docs-source/docs/antora.yml`, update `version: dev` to `version: current`
	yq write -i docs-source/docs/antora.yml version current
	# * In `docs/shared-content-source/docs/antora.yml`, update `version: x.y.z-SNAPSHOT` to `version: x.y.z`
	yq write -i shared-content-source/docs/antora.yml version ${version}
	# * In `examples/snippets/antora.yml`, update  `version: x.y.z-SNAPSHOT` to `version: x.y.z`
	yq write -i examples/snippets/antora.yml version ${version}
	# Change the internal version references to the new versions:
	# * In `docs/docs-source/docs/modules/ROOT/partials/include.adoc` 
	# ** update `:cloudflow-version:` to the new  `x.y.z` version
	# ** update `:cloudflow-examples-version:` to the new `x.y.z` version
	# yq write -i docs/docs-source/docs/modules/ROOT/partials/include.adoc cloudflow-version ${version}
	# yq write -i cloudflow-examples-version ${version}
	# * In `docs/shared-content-source/docs/modules/ROOT/partials/include.adoc` 
	# ** update `:cloudflow-version:` to the new  `x.y.z` version
	# ** update `:cloudflow-examples-version:` to the new `x.y.z` version
	# ** update `:cloudflow-branch-version:` to the exact tag of the released Cloudflow version (i.e vx.y.z)
