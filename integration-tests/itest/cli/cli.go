package cli

import (
	"fmt"
	"os/exec"
)

func Undeploy(appName string) error {
	fmt.Printf("Issuing Undeploy of app [%s]\n", appName)
	cmd := exec.Command("kubectl", "cloudflow", "undeploy", appName)
	_, err := cmd.CombinedOutput()
	return err
}
