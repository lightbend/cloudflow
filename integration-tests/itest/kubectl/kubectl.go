package kubectl

import (
	"fmt"
	"os/exec"
	"regexp"
	"strings"
	"time"
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

//PollUntilLogsContains polls the most recent logs of the specified pod and checks for
// the presence of the given string.
// Returns the line where the string is found.
func PollUntilLogsContains(pod string, namespace string, str string) (string, error) {
	lastNonEmptyLine := func(str string) string {
		lines := strings.Split(str, "\n")
		for i := len(lines) - 1; i >= 0; i-- {
			if len(strings.TrimSpace(lines[i])) > 0 {
				return lines[i]
			}
		}
		return ""
	}

	for {
		logs, err := GetLogs(pod, namespace, "1s")
		if err != nil {
			return "", err
		}
		lastLine := lastNonEmptyLine(logs)

		if strings.Contains(lastLine, str) == true {
			return lastLine, nil
		}
		time.Sleep(time.Second)
	}
}

// WaitUntilNoPods waits until there are no pods in the given namespace
func WaitUntilNoPods(namespace string) error {
	fmt.Printf("Waiting for no pods in namespace [%s]\n", namespace)
	sleepDuration, err := time.ParseDuration("1s")
	if err != nil {
		return err
	}

	first := true
	var pods []PodEntry

	for first || len(pods) > 0 {
		first = false
		time.Sleep(sleepDuration)
		pods, err = GetPods(namespace)
		if err != nil {
			return err
		}
		fmt.Printf("...%d", len(pods))
	}
	return nil
}
