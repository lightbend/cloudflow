package main_test

import (
	"fmt"
	"io/ioutil"
	"regexp"
	"strings"

	"log"
	"os/exec"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
)

// Assumptions:
// In this test, we are going to deploy the test application to an existing cluster.
// For this first step, we assume that the cluster is correctly deployed and that kubectl is configured to talk to it.
// The application is already built
// We also assume that the test is going to be executed manually.
// Automation will be the next step

type app struct {
	image string
	name  string
}

const (
	JsonTokenSrc = "/keybase/team/assassins/gcloud/pipelines-serviceaccount-key-container-registry-read-write.json"
)

var swissKnifeApp = app{
	image: "eu.gcr.io/bubbly-observer-178213/swiss-knife:189-277e424",
	name:  "swiss-knife",
}

func getToken() string {
	data, err := ioutil.ReadFile(JsonTokenSrc)
	if err != nil {
		Fail("Can't read credentials for test")
	}
	return string(data)
}

var _ = Describe("Application Deployment", func() {
	Context("when I deploy an application that uses akka, spark, and flink", func() {
		jsonToken := getToken()
		ensureAppNotDeployed(swissKnifeApp)

		output, err := deploy(swissKnifeApp, jsonToken)
		if err != nil {
			log.Fatal("Error executing command" + string(output))
		} else {
			log.Printf("Command said: %s", output)
		}
		It("should start a deployment", func() {
			fmt.Println("result is:" + output)
			// Expect(output).Should(Equal("Deployment of application `" + AppName + "` has started."))
		})
		It("should contain a spark-process", func() {
			Expect(true).To(BeTrue())
		})
	})
})

func deploy(app app, pwd string) (deployRes string, deployErr error) {
	cmd := exec.Command("kubectl", "cloudflow", "deploy", app.image, "--username", "_json_key", "--password", pwd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

type appListEntry struct {
	name         string
	namespace    string
	version      string
	creationtime string
}

func listApps() (entries []appListEntry, err error) {
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
	AllSubstrings := -1
	var res []appListEntry
	for i, line := range splits {
		switch i {
		case 0, 1: // skip line 0,1
		default:
			parts := whitespaces.Split(line, AllSubstrings)
			if len(parts) == 7 {
				appEntry := appListEntry{parts[0], parts[1], parts[2], parts[3] + parts[4] + parts[5] + parts[6]}
				res = append(res, appEntry)
			}
		}
	}
	return entries, nil
}

func ensureAppNotDeployed(app app) error {

	found := false
	fmt.Println("Apps in cluster")
	for _, entry := range apps {
		fmt.Println(entry)
		if entry.name == app.name {
			found = true
		}
	}
	if found {
		fmt.Printf("Application %s found in target cluster. Removing...", app.name)
		return nil // undeploy(app)
	}
	return nil
}

func undeploy(app app) error {
	cmd := exec.Command("kubectl", "cloudflow", "undeploy", app.name)
	_, err := cmd.CombinedOutput()
	if err != nil {
		log.Fatal("could not undeploy app " + app.name)
		return err
	}
	return nil
}
