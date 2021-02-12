#!/bin/bash
# Inspired by:
# https://github.com/vercel/install-node/blob/master/install.sh
# `get.sh` is a simple one-liner shell script to
# Install the official Cloudflow kubectl plugin
#
# TODO review instructions when is live
#   $ curl -sL http://cloudflow.io/get.sh | sh

# Install a specific version (ex: 2.0.22):
#
#   $ curl -sL http://cloudflow.io/get.sh/2.0.22 | sh
#
#
# Options may be passed to the shell script with `-s --`:
#
#   $ curl -sL http://cloudflow.io/get.sh | sh -s -- --prefix=$HOME --version=2.0.22 --verbose
#   $ curl -sL http://cloudflow.io/get.sh | sh -s -- -P $HOME -v 2.0.22 -V
#
set -euo pipefail

BOLD="$(tput bold 2>/dev/null || echo '')"
GREY="$(tput setaf 0 2>/dev/null || echo '')"
UNDERLINE="$(tput smul 2>/dev/null || echo '')"
RED="$(tput setaf 1 2>/dev/null || echo '')"
GREEN="$(tput setaf 2 2>/dev/null || echo '')"
YELLOW="$(tput setaf 3 2>/dev/null || echo '')"
BLUE="$(tput setaf 4 2>/dev/null || echo '')"
MAGENTA="$(tput setaf 5 2>/dev/null || echo '')"
CYAN="$(tput setaf 6 2>/dev/null || echo '')"
NO_COLOR="$(tput sgr0 2>/dev/null || echo '')"

info() {
  printf "${BOLD}${GREY}>${NO_COLOR} $@\n"
}

warn() {
  printf "${YELLOW}! $@${NO_COLOR}\n"
}

error() {
  printf "${RED}x $@${NO_COLOR}\n" >&2
}

complete() {
  printf "${GREEN}✓${NO_COLOR} $@\n"
}

fetch() {
  local command
  if hash curl 2>/dev/null; then
    set +e
    command="curl -L --silent --fail $1"
    curl -L --silent --fail "$1"
    rc=$?
    set -e
  else
    if hash wget 2>/dev/null; then
      set +e
      command="wget -O- -q $1"
      wget -O- -q "$1"
      rc=$?
      set -e
    else
      error "No HTTP download program (curl, wget) found…"
      exit 1
    fi
  fi

  if [ $rc -ne 0 ]; then
    error "Command failed (exit code $rc): ${BLUE}${command}${NO_COLOR}"
    exit $rc
  fi
}

resolve_cloudflow_version() {
  local tag="$1"
  local match_before="<span class=\"nav-text\">Cloudflow Docs - version "
  local match_after="<\/span>"
  local current=$(
    fetch "https://cloudflow.io/docs/current/index.html" | \
    grep "$match_before" | \
    sed "s/${match_before}//" | \
    sed "s/${match_after}//" | \
    tr -d '[:space:]')

  if [ "${tag}" = "${current}" ]; then
    tag=current
  fi

  local version=$(
    fetch "https://cloudflow.io/docs/$tag/index.html" | \
    grep "$match_before" | \
    sed "s/${match_before}//" | \
    sed "s/${match_after}//" | \
    tr -d '[:space:]')
  echo "$version"
}

# Currently known to support:
#   - win (Git Bash)
#   - darwin
#   - linux
# TODO test on Linux
detect_platform() {
  local platform="$(uname -s | tr '[:upper:]' '[:lower:]')"

  # mingw is Git-Bash
  if echo "${platform}" | grep -i mingw >/dev/null; then
    platform=win
  fi

  echo "${platform}"
}

# Currently known to support:
#   - x64 (x86_64)
detect_arch() {
  local arch="$(uname -m | tr '[:upper:]' '[:lower:]')"

  if echo "${arch}" | grep -i arm >/dev/null; then
    error "Detected ARM"
  else
    if [ "${arch}" = "i386" ]; then
      error "Detected i386"
    elif [ "${arch}" = "x86_64" ]; then
      arch=amd64
    elif [ "${arch}" = "aarch64" ]; then
      error "Detected aarch64"
    fi

    # `uname -m` in some cases mis-reports 32-bit OS as 64-bit, so double check
    if [ "${arch}" = "amd64" ] && [ "$(getconf LONG_BIT)" -eq 32 ]; then
      error "Detected 32 bit"
    else
      echo "${arch}"
    fi

  fi
}

confirm() {
  if [ -z "${FORCE-}" ]; then
    printf "${MAGENTA}?${NO_COLOR} $@ ${BOLD}[yN]${NO_COLOR} "
    set +e
    read yn < /dev/tty
    rc=$?
    set -e
    if [ $rc -ne 0 ]; then
      error "Error reading from prompt (please re-run with the \`--yes\` option)"
      exit 1
    fi
    if [ "$yn" != "y" ] && [ "$yn" != "yes" ]; then
      error "Aborting (please answer \"yes\" to continue)"
      exit 1
    fi
  fi
}

check_prefix() {
  local bin="$1"

  # https://stackoverflow.com/a/11655875
  local good=$( IFS=:
    for path in $PATH; do
      if [ "${path}" = "${bin}" ]; then
        echo 1
        break
      fi
    done
  )

  if [ "${good}" != "1" ]; then
    warn "Prefix bin directory ${bin} is not in your \$PATH"
  fi
}

# defaults
if [ -z "${VERSION-}" ]; then
  VERSION=current
fi

if [ -z "${PLATFORM-}" ]; then
  PLATFORM="$(detect_platform)"
fi

if [ -z "${PREFIX-}" ]; then
  PREFIX=/usr/local/bin
fi

if [ -z "${ARCH-}" ]; then
  ARCH="$(detect_arch)"
fi

if [ -z "${BASE_URL-}" ]; then
  BASE_URL="https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=kubectl-cloudflow-"
fi

# parse argv variables
while [ "$#" -gt 0 ]; do
  case "$1" in
    -v|--version) VERSION="$2"; shift 2;;
    -p|--platform) PLATFORM="$2"; shift 2;;
    -P|--prefix) PREFIX="$2"; shift 2;;
    -a|--arch) ARCH="$2"; shift 2;;
    -b|--base-url) BASE_URL="$2"; shift 2;;

    -V|--verbose) VERBOSE=1; shift 1;;
    -f|-y|--force|--yes) FORCE=1; shift 1;;

    -v=*|--version=*) VERSION="${1#*=}"; shift 1;;
    -p=*|--platform=*) PLATFORM="${1#*=}"; shift 1;;
    -P=*|--prefix=*) PREFIX="${1#*=}"; shift 1;;
    -a=*|--arch=*) ARCH="${1#*=}"; shift 1;;
    -b=*|--base-url=*) BASE_URL="${1#*=}"; shift 1;;
    -V=*|--verbose=*) VERBOSE="${1#*=}"; shift 1;;
    -f=*|-y=*|--force=*|--yes=*) FORCE="${1#*=}"; shift 1;;

    -*) error "Unknown option: $1"; exit 1;;
    *) VERSION="$1"; shift 1;;
  esac
done

# Resolve the requested version tag into an existing Node.js version
RESOLVED="$(resolve_cloudflow_version "$VERSION")"
if [ -z "${RESOLVED}" ]; then
  error "Could not resolve Cloudflow version ${MAGENTA}${VERSION}${NO_COLOR}"
  exit 1
fi

if [ -z "${ARCH}" ]; then
  error "Unsupported architecture"
  exit 1
fi


PRETTY_VERSION="${GREEN}${RESOLVED}${NO_COLOR}"
if [ "$RESOLVED" != "v$(echo "$VERSION" | sed 's/^v//')" ]; then
  PRETTY_VERSION="$PRETTY_VERSION (resolved from ${CYAN}${VERSION}${NO_COLOR})"
fi
printf "  ${UNDERLINE}Configuration${NO_COLOR}\n"
info "${BOLD}Version${NO_COLOR}:  ${PRETTY_VERSION}"
info "${BOLD}Prefix${NO_COLOR}:   ${GREEN}${PREFIX}${NO_COLOR}"
info "${BOLD}Platform${NO_COLOR}: ${GREEN}${PLATFORM}${NO_COLOR}"
info "${BOLD}Arch${NO_COLOR}:     ${GREEN}${ARCH}${NO_COLOR}"

# non-empty VERBOSE enables verbose untarring
if [ ! -z "${VERBOSE-}" ]; then
  VERBOSE=v
  info "${BOLD}Verbose${NO_COLOR}: yes"
else
  VERBOSE=
fi

echo

EXT=tar.gz

URL="${BASE_URL}${RESOLVED}-${PLATFORM}-${ARCH}.tar.gz"
info "Tarball URL: ${UNDERLINE}${BLUE}${URL}${NO_COLOR}"
check_prefix "${PREFIX}"
confirm "Install Cloudflow CLI ${GREEN}${RESOLVED}${NO_COLOR} to ${BOLD}${GREEN}${PREFIX}${NO_COLOR}?"

info "Installing Cloudflow CLI, please wait…"

rm -f "${PREFIX}/kubectl-cloudflow"

fetch "${URL}" \
  | tar xzf${VERBOSE} - \
    -C "${PREFIX}"

if [ "${PLATFORM}" = darwin ]; then
  ignored_failure=$(xattr -d com.apple.quarantine "${PREFIX}/kubectl-cloudflow" >/dev/null 2>/dev/null || true)
fi

complete "Done"
