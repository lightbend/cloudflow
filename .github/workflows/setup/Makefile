all: setup-local-all

setup-local-all: install-newer-sbt

install-newer-sbt:
	mkdir -p "${HOME}/opt"
	curl -Lo "${HOME}/opt/sbt.tgz" https://github.com/sbt/sbt/releases/download/v1.3.8/sbt-1.3.8.tgz
	tar xf "${HOME}/opt/sbt.tgz" -C "${HOME}/opt/"
