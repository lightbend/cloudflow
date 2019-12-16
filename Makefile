# Build the Cloudflow documentation

include definitions.mk

ROOT_DIR := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))

antora_docker_image     := lightbend/antora-doc
antora_docker_image_tag := 0.1.0

examples_src := docs-source/docs/modules/ROOT/examples
javascaladoc_src := docs-source/build/cloudflow-streamlets

staging_base_dir := docs-source/build/staging
staging_dir      := ${staging_base_dir}/cloudflow-documentation/${version}

all: build

clean:
	rm -rf docs-source/build ${examples_src}

build: clean html-staged javascaladoc_staged print-site

html: clean ${examples_src}
	docker run \
		-u $(shell id -u):$(shell id -g) \
		--privileged \
		-v ${ROOT_DIR}:/antora \
		--rm \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		--cache-dir=./.cache/antora \
		--stacktrace docs-source/site.yml
	@echo "Done"

html-author-mode: clean ${examples_src}
	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${ROOT_DIR}:/antora \
		--rm \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		--stacktrace docs-source/author-mode-site.yml
	@echo "Done"

check-links:
	docker run \
		-v ${ROOT_DIR}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		-c 'find /antora/docs-source -name '*.adoc' -print0 | xargs -0 -n1 asciidoc-link-check -p -c docs-source/asciidoc-link-check-config.json'

list-todos: html
	docker run \
		-v ${ROOT_DIR}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_docker_image_tag} \
		-c 'find /antora/docs-source/build/site/cloudflow/${version} -name "*.html" -print0 | xargs -0 grep -iE "TODO|FIXME|REVIEWERS|adoc"'

html-staged: ${staging_dir} html
	cp -r docs-source/build/site/* ${staging_dir}

# Generate the ScalaDoc and the JavaDoc, and put it in ${output}/scaladoc and ${output}/javadoc
javascaladoc: ${javascaladoc_src}
	(cd ${javascaladoc_src}/core && sbt clean unidoc)

javascaladoc_staged: ${staging_dir} javascaladoc
	cp -r ${javascaladoc_src}/core/target/scala-2.12/unidoc ${staging_dir}/scaladoc
	cp -r ${javascaladoc_src}/core/target/javaunidoc ${staging_dir}/javadoc

# Cloudflow streamlets source for java and scala doc
${javascaladoc_src}: ${staging_dir}
	git clone https://github.com/lightbend/cloudflow.git ${javascaladoc_src}
	# need to use the release branch during release
	# git clone --single-branch --branch v1.3.0 git@github.com:lightbend/cloudflow.git ${javascaladoc_src}

${staging_dir}:
	mkdir -p ${staging_dir}

${examples_src}: clean
	git clone https://github.com/lightbend/cloudflow.git ${examples_src}
	cp -r ${examples_src}/examples/* ${examples_src}/ 
	# need to use the release branch during release
	# git clone --single-branch --branch v1.3.0 git@github.com:lightbend/cloudflow.git ${examples_src}

print-site:
	# The result directory with the contents of this build:
	@echo "${staging_dir}"
