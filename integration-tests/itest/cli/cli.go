package cli

import (
	"fmt"
	"log"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

// App represents an application name and docker image
type App struct {
	Image string
	Name  string
}

// AppEntry represents an entry in the list of applications
type AppEntry struct {
	Name         string
	Namespace    string
	Version      string
	Creationtime string
}

// AppStatus represents the status of a deployed application
type AppStatus struct {
	Name          string
	Namespace     string
	Version       string
	Created       string
	Status        string
	StreamletPods []StreamletPod
}

// StreamletPod represents an instance of a streamlet's pod
type StreamletPod struct {
	Streamlet string
	Pod       string
	Status    string
	Restarts  int
	Ready     bool
}

// Deploy initiates the deployment of an application to the k8s cluster
func Deploy(app App, pwd string) (deployRes string, deployErr error) {
	cmd := exec.Command("kubectl", "cloudflow", "deploy", app.Image, "--username", "_json_key", "--password", pwd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// Undeploy initiates an application undeployment on the active cluster
func Undeploy(appName string) error {
	cmd := exec.Command("kubectl", "cloudflow", "undeploy", appName)
	_, err := cmd.CombinedOutput()
	return err
}

// ListApps returns the list of of deployed applications on the currently active cluster
func ListApps() (entries []AppEntry, err error) {
	cmd := exec.Command("kubectl", "cloudflow", "list")
	out, er := cmd.CombinedOutput()
	if er != nil {
		log.Fatal("could not check app status")
		err = er
		return
	}
	str := string(out)
	splits := strings.Split(str, "\n")
	whitespaces := regexp.MustCompile(`\s+`)
	var res []AppEntry
	for i, line := range splits {
		switch i {
		case 0, 1:
			continue
		default:
			parts := whitespaces.Split(line, -1)
			if len(parts) == 7 {
				appEntry := AppEntry{parts[0], parts[1], parts[2], parts[3] + parts[4] + parts[5] + parts[6]}

				res = append(res, appEntry)
			}
		}
	}
	return res, nil
}

// Status retrieves the current status of an application
func Status(app App) (status AppStatus, err error) {
	cmd := exec.Command("kubectl", "cloudflow", "status", app.Name)
	out, err := cmd.CombinedOutput()
	if err != nil {
		//err = er
		return
	}
	str := string(out)
	splits := strings.Split(str, "\n")

	names := [5]string{"Name:", "Namespace:", "Version:", "Created:", "Status:"}
	fields := [5]*string{&status.Name, &status.Namespace, &status.Version, &status.Created, &status.Status}
	var streamletPods []StreamletPod

	for i, line := range splits {

		switch i {
		case 0, 1, 2, 3, 4:
			value, er := parseLineInN(line, 2)
			if er != nil {
				err = er
				return
			}
			if value[0] != names[i] {
				err = fmt.Errorf("unexpected header name. Got [%s] but expected [%s]", value[0], names[i])
				return
			}
			*fields[i] = value[1]
		case 5:
			continue // separator line
		case 6:
			continue // titles
		default:
			if len(strings.TrimSpace(line)) == 0 {
				continue
			}
			parts, er := parseLineInN(line, 5)
			if er != nil {
				err = er
				return
			}
			var streamletPod StreamletPod
			streamletPod.Streamlet = parts[0]
			streamletPod.Pod = parts[1]
			streamletPod.Status = parts[2]
			restarts, er := strconv.Atoi(parts[3])
			if er != nil {
				return AppStatus{}, err
			}
			streamletPod.Restarts = restarts
			streamletPod.Ready = strings.TrimSpace(parts[4]) == "True"
			streamletPods = append(streamletPods, streamletPod)
		}
	}
	status.StreamletPods = streamletPods
	return status, nil
}

func parseLineInN(str string, segments int) (parsed []string, err error) {
	whitespaces := regexp.MustCompile(`\s+`)
	parts := whitespaces.Split(str, -1)
	if len(parts) >= segments {
		for i, part := range parts {
			parts[i] = strings.TrimSpace(part)
		}
		return parts, nil
	}
	err = fmt.Errorf("string didn't contain [%d] separate words: [%s]", segments, str)
	return
}

// GetStreamlets retrieves the streamlets from an AppStatus instance
func GetStreamlets(appStatus *AppStatus) []string {
	var res []string
	for _, entry := range appStatus.StreamletPods {
		res = append(res, entry.Streamlet)
	}
	return res
}

// GetStreamletPod retrieves the streamletPods from an AppStatus instance
func GetStreamletPod(status *AppStatus, streamlet string) *StreamletPod {
	for _, entry := range status.StreamletPods {
		if entry.Streamlet == streamlet {
			return &entry
		}
	}
	return nil
}

// GetSinglePodForStreamlet returns a pod associated to the streamlet runtime
func GetSinglePodForStreamlet(app App, streamlet string) (pod string, err error) {
	status, er := Status(app)
	if er != nil {
		err = er
		return
	}
	streamletPod := GetStreamletPod(&status, streamlet)
	if streamletPod == nil {
		err = fmt.Errorf("could not find entry for streamlet [%s]", streamlet)
		return
	}
	return streamletPod.Pod, nil
}
