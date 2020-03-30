package fileutils

import (
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const (
	fetchTimeout = 30
)

// GetFileContents gets the contents of a local file or of a remote url.
// A proper error the local file does not exist or data cannot be fetched from the remote url.
func GetFileContents(pathToFile string) (string, error) {
	url, err := url.Parse(pathToFile)
	if url == nil || err != nil {
		return "", fmt.Errorf("You need to specify the full local path to a file or to a remote url. %s, is malformed. Error: %s", pathToFile, err.Error())
	}
	if url.Scheme == "" { // this is a local file
		var sb strings.Builder
		content, err := ioutil.ReadFile(pathToFile)
		if err != nil {
			return "", fmt.Errorf("could not read configuration file %s", content)
		}
		sb.Write(content)
		return sb.String(), nil
	}

	// fetch url
	client := http.Client{
		Timeout: fetchTimeout * time.Second,
	}

	// http client handlers redirects
	resp, err := client.Get(pathToFile)

	if err != nil {
		return "", fmt.Errorf("You need to specify the full local path to a file or to a remote url. %s, is not accessible. Error: %s ", pathToFile, err.Error())
	}

	if resp == nil || resp.StatusCode != 200 {
		return "", fmt.Errorf("You need to specify the full local path to a file or to a remote url. %s, is not accessible", pathToFile)
	}

	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("Could not read the remote file.  Error: %s", err.Error())
	}
	contents := string(body)
	return contents, nil
}

// FileExists checks if a file already exists at a given path
func FileExists(filename string) bool {
	info, err := os.Stat(filename)
	if os.IsNotExist(err) {
		return false
	}
	return !info.IsDir()
}