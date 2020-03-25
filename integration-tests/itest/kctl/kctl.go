package kctl

import (
	"os/exec"
	"regexp"
	"strings"
)

// PodEntry describes a pod
type PodEntry struct {
	name     string
	ready    string
	status   string
	restarts string
	age      string
}

// GetLogs retrieves the most recent logs for a given pod in a namespace for the time speficied.
// e.g.: is `since` is 1s, GetLogs will retrive the logs of the lastest second.
func GetLogs(pod string, namespace string, since string) (logs string, err error) {
	sinceParam := "--since=" + since
	cmd := exec.Command("kubectl", "logs", pod, "-n", namespace, sinceParam)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// GetPods retrieves the list of pods in a namespace
func GetPods(namespace string) (pods []PodEntry, err error) {
	cmd := exec.Command("kubectl", "get", "pods", "-n", namespace)
	out, er := cmd.CombinedOutput()
	if er != nil {
		err = er
		return
	}
	str := string(out)
	splits := strings.Split(str, "\n")
	whitespaces := regexp.MustCompile(`\s+`)
	var res []PodEntry
	for i, line := range splits {
		switch i {
		case 0:
			continue
		default:
			parts := whitespaces.Split(line, -1)
			if len(parts) == 5 {
				podEntry := PodEntry{parts[0], parts[1], parts[2], parts[3], parts[4]}
				res = append(res, podEntry)
			}
		}
	}
	return res, nil
}
