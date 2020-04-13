#!/bin/bash

if [ -z "$1" ]; then
    echo "Please specifiy a release tag"
    exit 1
else
  releaseTag=$1
  echo "Release tag specified: $releaseTag"
fi

if [ -z "$2" ]; then
  branch="$(git rev-parse --abbrev-ref HEAD)"
  echo "Branch not specified. Detected: $branch"
else
  branch="$2"
  echo "Branch specified: $branch"
fi

bintrayUsername=$BINTRAY_USERNAME
bintrayPassword=$BINTRAY_PASSWORD

if [ -z "$BINTRAY_USERNAME" ] || [ -z "$BINTRAY_PASSWORD" ]; then
  credentialsFile=".bintray-publish-credentials"
  cloudflowPath="$HOME/.lightbend/cloudflow"

  if [ -f  "$HOME/$credentialsFile" ]; then
    bintrayUsername=$(awk '/user/{print $NF}' "$HOME/$credentialsFile")
    bintrayPassword=$(awk '/password/{print $NF}' "$HOME/$credentialsFile")
  elif [ -f "$cloudflowPath/$credentialsFile" ]; then
    bintrayUsername=$(awk '/user/{print $NF}' "$cloudflowPath/$credentialsFile")
    bintrayPassword=$(awk '/password/{print $NF}' "$cloudflowPath/$credentialsFile")
  else
    echo "Please provide Bintray credentials in one of the following ways:"
    echo "- Set BINTRAY_USERNAME and BINTRAY_PASSWORD environment variables"
    echo "- Create a .bintray-publish-credentials file in $HOME"
    echo "- Create a .bintray-publish-credentials file in $cloudflowPath."
    exit 1
  fi
fi

commits="$(git rev-list --count HEAD)"
revision="$(git rev-parse --short HEAD)"
buildNumber="$commits-$revision"

applicationName="kubectl-cloudflow"
windowsApplicationName="$applicationName.exe"

buildDir=$PWD

baseTargetPath="binaries/amd64"
darwinTargetPath="$baseTargetPath/darwin"
linuxTargetPath="$baseTargetPath/linux"
windowsTargetPath="$baseTargetPath/windows"

darwinArchive="$applicationName-$releaseTag.$buildNumber-darwin-amd64.tar.gz"
linuxArchive="$applicationName-$releaseTag.$buildNumber-linux-amd64.tar.gz"
windowsArchive="$applicationName-$releaseTag.$buildNumber-windows-amd64.tar.gz"

bintrayPackageURL="https://api.bintray.com/content/lightbend/cloudflow-cli/$applicationName"
bintrayURL="$bintrayPackageURL/$releaseTag.$buildNumber"

function upload_binaries {
  echo "Releasing MacOS archive..."
  upload_binary $darwinTargetPath $applicationName $darwinArchive

  echo "Releasing Linux archive..."
  upload_binary $linuxTargetPath $applicationName $linuxArchive

  echo "Releasing Windows archive..."
  upload_binary $windowsTargetPath $windowsApplicationName $windowsArchive

  if [ "$uploaded" = true ] ; then
    echo "Release succeeded."
    echo ""
    echo "Download links"
    echo "https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=$darwinArchive"
    echo "https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=$linuxArchive"
    echo "https://bintray.com/lightbend/cloudflow-cli/download_file?file_path=$windowsArchive"
    echo ""
  else
    exit 1
  fi
}

function upload_binary {
  targetPath=$1
  binary=$2
  tarFile=$3

  cd $targetPath
  chmod +x $binary
  tar -czf $tarFile $binary
  uploaded=false

  response=$(curl -s -T $tarFile -u$bintrayUsername:$bintrayPassword $bintrayURL/$tarFile?publish=1)
  if [[ $response = *"{\"message\":\"success\"}"* ]]; then
    uploaded=true
  else
    unset uploaded
    echo "Could NOT upload archive, bintray responded with: $response"
  fi
  cd $buildDir
}

function tag_repo {
  git tag -a v$releaseTag -m "Release $releaseTag"
  currentRemote=$(git config branch.$(git rev-parse --abbrev-ref HEAD).remote)
  git push $currentRemote v$releaseTag
}

# =============================================================================
# Main script
# =============================================================================

echo ""
echo "Building kubectl-cloudflow from branch $branch..."

if ! [ -x "$(command -v dep)" ]; then
  echo "Installing the dep tool..."
  curl https://raw.githubusercontent.com/golang/dep/master/install.sh | sh
fi

echo ""
echo "Updating dependencies..."
go mod tidy

echo ""
echo "Building binaries for version ${buildNumber}..."

echo "Building MacOS as $darwinTargetPath/$applicationName..."
cmd="GOOS=darwin CGO_ENABLED=0 GOARCH=amd64 go build -ldflags "-extldflags "-static""  '-X \"github.com/lightbend/cloudflow/kubectl-cloudflow/version.BuildNumber=${buildNumber}\" -X \"github.com/lightbend/cloudflow/kubectl-cloudflow/version.ReleaseTag=${releaseTag}\"'  -tags "exclude_graphdriver_devicemapper exclude_graphdriver_btrfs containers_image_openpgp" -o $darwinTargetPath/$applicationName"
eval $cmd

echo "Building Linux as $linuxTargetPath/$applicationName..."
cmd="GOOS=linux CGO_ENABLED=0 GOARCH=amd64 go build -ldflags "-extldflags "-static"" '-X \"github.com/lightbend/cloudflow/kubectl-cloudflow/version.BuildNumber=${buildNumber}\" -X \"github.com/lightbend/cloudflow/kubectl-cloudflow/version.ReleaseTag=${releaseTag}\"' -tags "exclude_graphdriver_devicemapper exclude_graphdriver_btrfs containers_image_openpgp" -o $linuxTargetPath/$applicationName"
eval $cmd

echo "Building Windows as $windowsTargetPath/$windowsApplicationName..."
cmd="GOOS=windows CGO_ENABLED=0 GOARCH=amd64 go build -ldflags "-extldflags "-static"" '-X \"github.com/lightbend/cloudflow/kubectl-cloudflow/version.BuildNumber=${buildNumber}\" -X \"github.com/lightbend/cloudflow/kubectl-cloudflow/version.ReleaseTag=${releaseTag}\"' -tags "exclude_graphdriver_devicemapper exclude_graphdriver_btrfs containers_image_openpgp" -o $windowsTargetPath/$windowsApplicationName"
eval $cmd

echo ""
if [ $branch = "master" ] || [ $branch = "origin/master" ]; then
  echo "On master branch. Releasing binaries to Bintray..."
  upload_binaries
  tag_repo
else
  echo "Current branch: $branch"
  echo "Not on 'master'."
  echo "Not releasing the binaries to Bintray."
fi
