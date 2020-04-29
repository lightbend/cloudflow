# Cloudflow Documentation

This repository contains the sources for the [Cloudflow web site](https://cloudflow.io/) and the [Cloudflow documentation](https://cloudflow.io/docs)

This repository is structured as follows:
- The root directory contains the `makefile` for the documentation generation process.
- The source files for the home page and other `root` pages in the website are located under `homepage-source/`.
- The structured documentation is located under `docs-source/`, with content that is shareable located in `shared-content-source`. See the readme.adoc file in the `shared-content-source` folder for more information.

Additionally, the style used to generate the published website is available at: https://github.com/lightbend/antora-ui-lightbend-cloud-theme

Contributions to the documentation are welcome and encouraged.
If you are unfamiliar with the project or with asciidoc, please read the contribution guidelines below.

## Contributing to the Cloudflow Documentation

Detailed information about working with the documentation is provided in the [docs-source](docs-source/README.adoc) folder.

## Building the Documentation

The Cloudflow documentation is built using [Antora](https://docs.antora.org/antora/2.1/), from asciidoc sources.
The building process is managed by `make` using the [makefile](./makefile) script.

To build the documentation, use `make` with the following commands:

`make all` (default):: 
	Generates the complete documentation bundle, including the Cloudflow docs, the website pages, and the java and scala docs.
	The result is available at `docs-source/build/site/`

`make html`::
    Generates the html documentation and homepage. The result is available at `techhub/build/site/`.

`make html-author-mode`:: 
    Generates the documentation, in 'author' mode, to display review comments and TODOs. The result is available at `techhub/build/site/cloudflow/<version>`.

* `make check-links`

    Checks that the external links point to a reachable URL.

* `make list-todos`

    List all the TODOs, review comments, unresolve references, etc. from the documentation.


### Updating a current version of the documentation

T B W

### Adding a new version of the documentation

TBW
> **TODO**: copy the asciidoc help from the old `user-guide` README.
