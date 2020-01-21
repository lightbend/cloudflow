package fileutil

import (
	"fmt"
	"io/ioutil"
	"net/url"
	"os"
	"strings"
)

// FileExists checks if a file already exists at a given path
func FileExists(filename string) bool {
	info, err := os.Stat(filename)
	if os.IsNotExist(err) {
		return false
	}
	return !info.IsDir()
}

// GetFileContents gets the contents of a local file or of a remote url.
// A proper error the local file does not exist or data cannot be fetched from the remote url.
func GetFileContents(pathToFile string) (string, error) {
	url, err := url.Parse(pathToFile)
	if url == nil || err != nil {
		return "", fmt.Errorf("You need to specify the full local path to a file. %s, is malformed. Error: %s", pathToFile, err.Error())
	}
	if url.Scheme != "" {
		return "", fmt.Errorf("Only local file is supported, passed name (%s)", pathToFile)
	}
	var sb strings.Builder
	content, err := ioutil.ReadFile(pathToFile)
	if err != nil {
		return "", fmt.Errorf("could not read configuration file %s", content)
	}
	sb.Write(content)
	return sb.String(), nil
}
