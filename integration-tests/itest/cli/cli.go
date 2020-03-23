package cli

import (
	"fmt"
	"log"
	"os/exec"
	"regexp"
	"strings"
)

// AppEntry represents an entry in the list of applications
type AppEntry struct {
	Name         string
	Namespace    string
	Version      string
	Creationtime string
}

// Undeploy initiates an application undeployment on the active cluster
func Undeploy(appName string) error {
	fmt.Printf("Issuing Undeploy of app [%s]\n", appName)
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
