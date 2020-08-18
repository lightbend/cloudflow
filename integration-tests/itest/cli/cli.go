package cli

import (
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// App represents an application name and docker image
type App struct {
	CRFile string
	Name   string
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
	Streamlet     string
	Pod           string
	ActualReady   int
	ExpectedReady int
	Status        string
	Restarts      int
}

var pollSleepInterval, _ = time.ParseDuration("5s")

func logOutputIfFailure(command string, output []byte, err error) {
	if err != nil {
		fmt.Printf("[%s] error. Output: [%s] Error code: [%s]", command, output, err.Error())
	}
}

// Deploy initiates the deployment of an application to the k8s cluster
func Deploy(app App, user string, pwd string) (deployRes string, deployErr error) {
	cmd := exec.Command("kubectl", "cloudflow", "deploy", app.CRFile, "--no-registry-credentials")
	out, err := cmd.CombinedOutput()
	logOutputIfFailure("deploy", out, err)
	return string(out), err
}

// Undeploy initiates an application undeployment on the active cluster
func Undeploy(app App) error {
	cmd := exec.Command("kubectl", "cloudflow", "undeploy", app.Name)
	out, err := cmd.CombinedOutput()
	logOutputIfFailure("undeploy", out, err)
	return err
}

// Scale changes the scale factor of a streamlet in an app
func Scale(app App, streamlet string, scale int) error {
	cmd := exec.Command("kubectl", "cloudflow", "scale", app.Name, streamlet, strconv.Itoa(scale))
	out, err := cmd.CombinedOutput()
	logOutputIfFailure("scale", out, err)
	return err
}

// Configure applies a configuration file to a given application
func Configure(app App, filePath string) error {
	cmd := exec.Command("kubectl", "cloudflow", "configure", app.Name, "--conf", filePath)
	out, err := cmd.CombinedOutput()
	logOutputIfFailure("configure", out, err)
	return err
}

// ListApps returns the list of of deployed applications on the currently active cluster
func ListApps() (entries []AppEntry, err error) {
	cmd := exec.Command("kubectl", "cloudflow", "list")
	out, err := cmd.CombinedOutput()
	var res []AppEntry
	logOutputIfFailure("list", out, err)
	if err != nil {
		return res, err
	}
	str := string(out)
	splits := strings.Split(str, "\n")
	whitespaces := regexp.MustCompile(`\s+`)
	for i, line := range splits {
		switch i {
		case 0: // skip the first line
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

// ListAppNames returns the list of the names of the apps deployed applications on the currently active cluster.
// This method is similar to ListApps() but returns only the name of the apps for easy use.
func ListAppNames() (appNames []string, err error) {
	apps, err := ListApps()
	if err != nil {
		return
	}
	list := make([]string, len(apps))
	for _, entry := range apps {
		list = append(list, entry.Name)
	}
	return list, nil
}

// Status retrieves the current status of an application
func Status(app App) (status AppStatus, err error) {
	cmd := exec.Command("kubectl", "cloudflow", "status", app.Name)
	out, err := cmd.CombinedOutput()
	logOutputIfFailure("status", out, err)
	mkErr := func(err error) (AppStatus, error) {
		return AppStatus{}, err
	}
	if err != nil {
		return mkErr(err)
	}
	str := string(out)
	splits := strings.Split(str, "\n")

	names := [5]string{"Name:", "Namespace:", "Version:", "Created:", "Status:"}
	fields := [5]*string{&status.Name, &status.Namespace, &status.Version, &status.Created, &status.Status}
	var streamletPods []StreamletPod

	for i, line := range splits {

		switch i {
		case 0, 1, 2, 3, 4:
			value, err := parseLineInN(line, 2)
			if err != nil {
				return mkErr(err)
			}
			if value[0] != names[i] {
				err = fmt.Errorf("Unexpected header name. Got [%s] but expected [%s]", value[0], names[i])
				return mkErr(err)
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
			parts, err := parseLineInN(line, 4)
			if err != nil {
				return mkErr(err)
			}
			var streamletPod StreamletPod
			streamletPod.Streamlet = parts[0]

			var parseErr error
			if len(parts) == 4 { // POD = empty
				streamletPod.Pod = ""
				streamletPod, parseErr = parseStreamletPod(streamletPod, parts[1:4])
			} else {
				streamletPod.Pod = parts[1]
				streamletPod, parseErr = parseStreamletPod(streamletPod, parts[2:5])
			}

			if parseErr != nil {
				return mkErr(parseErr)
			}
			streamletPods = append(streamletPods, streamletPod)
		}
	}
	status.StreamletPods = streamletPods
	return status, nil
}

func parseStreamletPod(streamletPod StreamletPod, elems []string) (StreamletPod, error) {
	var err error
	streamletPod.ActualReady, streamletPod.ExpectedReady, err = parseReady(elems[0])
	if err != nil {
		return streamletPod, err
	}
	streamletPod.Status = elems[1]
	streamletPod.Restarts, err = strconv.Atoi(elems[2])
	if err != nil {
		return streamletPod, err
	}
	return streamletPod, nil
}

func parseReady(str string) (actual int, expected int, err error) {
	splits := strings.Split(str, "/")
	if len(splits) != 2 {
		err = fmt.Errorf("string didn't contain actual/expected counts: [%s]", str)
		return
	}
	if actual, err = strconv.Atoi(splits[0]); err != nil {
		return
	}
	if expected, err = strconv.Atoi(splits[1]); err != nil {
		return
	}
	return actual, expected, nil
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
	streamletMap := make(map[string]bool)
	for _, entry := range appStatus.StreamletPods {
		streamletMap[entry.Streamlet] = true
	}
	var res []string
	for key := range streamletMap {
		res = append(res, key)
	}
	return res
}

// GetFirstStreamletPod retrieves the streamletPods from an AppStatus instance
func GetFirstStreamletPod(status *AppStatus, streamlet string) *StreamletPod {
	for _, entry := range status.StreamletPods {
		if entry.Streamlet == streamlet {
			return &entry
		}
	}
	return nil
}

// GetPods retrieves the pods corresponding to the given streamlet from an AppStatus instance
func GetPods(status *AppStatus, streamlet string) []string {
	var res []string
	for _, entry := range status.StreamletPods {
		if entry.Streamlet == streamlet {
			res = append(res, entry.Pod)
		}
	}
	return res
}

// GetOneOfThePodsForStreamlet returns a pod associated to the streamlet runtime
func GetOneOfThePodsForStreamlet(app App, streamlet string) (pod string, err error) {
	status, err := Status(app)
	if err != nil {
		return
	}
	pods := GetPods(&status, streamlet)
	if pods == nil {
		err = fmt.Errorf("could not find entry for streamlet [%s]", streamlet)
		return
	}
	return pods[0], nil
}

// GetPodsForStreamlet retrieves the pods that correspond to the given streamlet.
func GetPodsForStreamlet(app App, streamlet string) (pods []string, err error) {
	status, err := Status(app)
	if err != nil {
		return nil, err
	}
	return GetPods(&status, streamlet), nil
}

// PollUntilExpectedPodsForStreamlet retrieves the pods that correspond to the given streamlet when
// the pod count is or becomes equal to the expected count.
func PollUntilExpectedPodsForStreamlet(app App, streamlet string, expected int) (pods []string, err error) {
	for {
		pods, err = GetPodsForStreamlet(app, streamlet)
		if err != nil {
			return
		}
		if len(pods) == expected {
			return
		}
		time.Sleep(pollSleepInterval)
	}
}

// PollUntilAppStatusIs polls the status of the application until it matches the expected status
func PollUntilAppStatusIs(app App, expected string) (res string, err error) {
	for {
		appStatus, er := Status(app)
		if er != nil {
			err = er
			return
		}

		if appStatus.Status == expected {
			return expected, nil
		}

		time.Sleep(pollSleepInterval)
	}
}

// PollUntilPodsStatusIs polls the status of each pod of the application to be the expected status
// returns when all pods have the expected status.
func PollUntilPodsStatusIs(app App, expected string) (res string, err error) {
	for {
		appStatus, er := Status(app)
		if er != nil {
			err = er
			return
		}
		allSame := true
		for _, entry := range appStatus.StreamletPods {
			allSame = allSame && entry.Status == expected
			if !allSame {
				break
			}
		}
		if allSame {
			return expected, nil
		}
		time.Sleep(pollSleepInterval)
	}
}

// PollUntilAppPresenceIs polls the list API for the presence of the app.
// The end condition depends on the `expected` flag
// If `expected` is true, this method will poll until the app is found (present)
// If `expected` is false, this method will poll until the app is not found (absent)
func PollUntilAppPresenceIs(app App, expected bool) error {
	for {
		apps, err := ListApps()
		if err != nil {
			return err
		}
		found := false
		for _, entry := range apps {
			if entry.Name == app.Name {
				found = true
				break
			}
		}
		if found == expected {
			return nil
		}
		time.Sleep(pollSleepInterval)
	}
}
