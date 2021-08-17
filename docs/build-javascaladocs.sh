#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR=$( cd "${SCRIPT_DIR}/.." && pwd )
DOCS_DIR="$ROOT_DIR/docs"

# Keep this consistent with the `work_dir` in the Makefile.
TARGET_DIR="$DOCS_DIR/target"
CLOUDFLOW_CLONE_DIR="${TARGET_DIR}/cloudflow"

echo "SCRIPT_DIR          => $SCRIPT_DIR"
echo "ROOT_DIR            => $ROOT_DIR"
echo "DOCS_DIR            => $DOCS_DIR"
echo "TARGET_DIR          => $TARGET_DIR"
echo "CLOUDFLOW_CLONE_DIR => $CLOUDFLOW_CLONE_DIR"

versions=$(yq e '.content.sources[0].branches[]' "$DOCS_DIR/docs-source/site.yml")

if [ -z "$versions" ]; then
  echo "No version branches found in $DOCS_DIR/docs-source/site.yml. See file content below:"
  cat "$DOCS_DIR/docs-source/site.yml"
  exit 1
fi

# Recreating the target dir before generating the new docs.
rm -rf "$TARGET_DIR/cloudflow"
mkdir -p -v "$TARGET_DIR"

git clone https://github.com/lightbend/cloudflow.git "$CLOUDFLOW_CLONE_DIR"

FAILURES=()

for version in $versions; do
  echo "Building docs for version $version"
  cd "$CLOUDFLOW_CLONE_DIR" || exit 1 # Maybe if failed to clone the cloudflow repo.
  git checkout --quiet "$version"
  cd "$CLOUDFLOW_CLONE_DIR/core" || exit 1 # core directory does not exists anymore?

  if ! sbt --error --supershell=false -mem 2048 clean unidoc; then
    FAILURES+=("$version")
  fi

  ls -al "$CLOUDFLOW_CLONE_DIR/core/target"
  ls -al "$CLOUDFLOW_CLONE_DIR/core/target/scala-2.12"

  DIR=$(yq e '.version' "$CLOUDFLOW_CLONE_DIR/docs/docs-source/docs/antora.yml")
  mkdir -p "$TARGET_DIR/staging/docs/$DIR/api"

  mv -v "$CLOUDFLOW_CLONE_DIR/core/target/scala-2.12/unidoc" "$TARGET_DIR/staging/docs/$DIR/api/scaladoc"
  mv -v "$CLOUDFLOW_CLONE_DIR/core/target/javaunidoc"        "$TARGET_DIR/staging/docs/$DIR/api/javadoc"
done

# So that we can at least log the errors, but it won't fail the script.
if [ ${#FAILURES[@]} -gt 0 ]; then
  printf "Failed to generate docs for version %s\n" "${FAILURES[@]}"
  cd "$ROOT_DIR" || true # get back home
  exit 0
fi
