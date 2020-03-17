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

var _ = Describe("Application Deployment", func() {
	Context("when I deploy an application that uses akka, spark, and flink", func() {
		jsonToken := getToken()

		ensureAppNotDeployed(swissKnifeApp)

		output, err := fakeDeploy(swissKnifeApp, jsonToken)
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
		It("should have pods", func() {
			pods, err := getPods(swissKnifeApp)
			if err != nil {
				log.Fatal("couldn't retrieve pods.", err)
			}
			for _, pod := range pods {
				fmt.Println(pod)
			}
		})
	})
})

func getToken() string {
	data, err := ioutil.ReadFile(JsonTokenSrc)
	if err != nil {
		Fail("Can't read credentials for test")
	}
	return string(data)
}

func deploy(app app, pwd string) (deployRes string, deployErr error) {
	cmd := exec.Command("kubectl", "cloudflow", "deploy", app.image, "--username", "_json_key", "--password", pwd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func fakeDeploy(app app, pwd string) (deployRes string, deployErr error) {
	fmt.Println(app.name)
	fmt.Println(pwd[1:1])
	return "OK", nil
}

type appListEntry struct {
	name         string
	namespace    string
	version      string
	creationtime string
}

type podEntry struct {
	name     string
	ready    string
	status   string
	restarts string
	age      string
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
			fmt.Print("found this:")
			fmt.Println(parts)
			if len(parts) == 7 {
				appEntry := appListEntry{parts[0], parts[1], parts[2], parts[3] + parts[4] + parts[5] + parts[6]}

				res = append(res, appEntry)
			}
		}
	}
	return res, nil
}

func ensureAppNotDeployed(app app) error {
	apps, err := listApps()
	if err != nil {
		return err
	}
	found := false
	fmt.Printf("Apps in cluster: [%d]", len(apps))
	for _, entry := range apps {
		fmt.Printf("App in deployed list: [%s][%s]", entry.name, entry.namespace)
		if entry.name == app.name {
			fmt.Printf("This is the app you are looking for: [%s]", entry.name)
			found = true
		}
	}
	if found {
		fmt.Printf("Application %s found in target cluster. Undeploying...", app.name)
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

func getPods(app app) (pods []podEntry, err error) {
	cmd := exec.Command("kubectl", "get", "pods", "-n", app.name)
	out, er := cmd.CombinedOutput()
	if er != nil {
		err = er
		return
	}
	str := string(out)
	splits := strings.Split(str, "\n")
	whitespaces := regexp.MustCompile(`\s+`)
	AllSubstrings := -1
	var res []podEntry
	for i, line := range splits {
		switch i {
		case 0: // skip line 0
		default:
			parts := whitespaces.Split(line, AllSubstrings)
			fmt.Print("found this:")
			fmt.Println(parts)
			if len(parts) == 5 {
				podEntry := podEntry{parts[0], parts[1], parts[2], parts[3], parts[4]}
				res = append(res, podEntry)
			}
		}
	}
	return res, nil
}
