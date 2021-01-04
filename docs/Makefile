# Build the Cloudflow documentation

include definitions.mk

SHELL_DIR := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
ROOT_DIR := ${SHELL_DIR}/..

antora_docker_image     := lightbend/antora-cloudflow-doc
antora_docker_image_tag := 2.3.2

work_dir := ${ROOT_DIR}/docs/target

staging_dir := ${work_dir}/staging

all: build

local-preview: html-author-mode
	@echo "Access the documentation on http://localhost:8000"
	(cd target/staging/; python3 -m http.server)


show:
	echo work dir: ${work_dir}
	echo ROOT_DIR: ${ROOT_DIR}

clean:
	rm -rf ${work_dir}

build: clean html all_javascaladocs print-site

get-version:
	# get latest tag
	$(eval version=$(shell sh -c "git for-each-ref refs/tags --sort=-taggerdate --format='%(refname)' --count=1 | sed 's|refs/tags/v||'"))
	# query bintray for the corresponding artifact version
	$(eval bintray_version=$(shell sh -c "wget -qO - https://api.bintray.com/packages/lightbend/cloudflow-cli/kubectl-cloudflow | jq -r '.versions[] | select( . == \"cloudflow_v${version}\")'"))

set-antora-versions: get-version
	yq e ".version = \"${version}\"" shared-content-source/docs/base-antora.yml > shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-version = \"${version}\"" shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-examples-version = \"${version}\"" shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-branch-version = \"v${version}\"" shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.kubectl-plugin-version = \"${bintray_version}\" shared-content-source/docs/antora.yml

	yq e ".version = \"${version}\"" ../examples/snippets/base-antora.yml > ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-version = \"${version}\"" ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-examples-version = \"${version}\"" ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-branch-version = \"v${version}\"" ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.kubectl-plugin-version = \"${bintray_version}\" ../examples/snippets/antora.yml

	yq e ".version = \"${version}\"" docs-source/docs/base-antora.yml > docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-version = \"${version}\"" docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-examples-version = \"${version}\"" docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-branch-version = \"v${version}\"" docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.kubectl-plugin-version = \"${bintray_version}\" docs-source/docs/antora.yml

set-antora-versions-to-dev: get-version
	yq e ".version = \"dev\"" shared-content-source/docs/base-antora.yml > shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-version = \"dev\"" shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-examples-version = \"dev\"" shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-branch-version = \"dev\"" shared-content-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.kubectl-plugin-version = \"not-available\"" shared-content-source/docs/antora.yml

	yq e ".version = \"dev\"" ../examples/snippets/base-antora.yml > ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-version = \"dev\"" ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-examples-version = \"dev\"" ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-branch-version = \"dev\"" ../examples/snippets/antora.yml
	yq e -i ".asciidoc.attributes.kubectl-plugin-version = \"not-available\"" ../examples/snippets/antora.yml

	yq e ".version = \"dev\"" docs-source/docs/base-antora.yml > docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-version = \"dev\"" docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-examples-version = \"dev\"" docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.cloudflow-branch-version = \"dev\"" docs-source/docs/antora.yml
	yq e -i ".asciidoc.attributes.kubectl-plugin-version = \"not-available\"" docs-source/docs/antora.yml

html: clean
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

html-author-mode: clean
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

# Generate the ScalaDoc and the JavaDoc, for the old versions
all_javascaladocs:
	@echo "Building old java and scala docs"
	(yq e '.content.sources[0].branches.*' docs-source/site.yml | xargs -n1 ./build-javascaladocs.sh)

print-site:
	# The result directory with the contents of this build:
	@echo "${staging_dir}"
