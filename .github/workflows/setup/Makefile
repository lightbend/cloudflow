all: setup-local-all

setup-local-all: install-newer-sbt

install-newer-sbt:
	mkdir -p "${HOME}/opt"
	curl -Lo "${HOME}/opt/sbt.tgz" https://piccolo.link/sbt-1.2.8.tgz
	tar xf "${HOME}/opt/sbt.tgz" -C "${HOME}/opt/"
