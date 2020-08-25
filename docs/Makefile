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
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		--cache-dir=./.cache/antora \
		-c 'find /antora/docs-source -name '*.adoc' -print0 | xargs -0 -n1 asciidoc-link-check -p -c docs-source/asciidoc-link-check-config.json'

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
