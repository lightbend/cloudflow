package main_test

import (
	"fmt"
	"io/ioutil"
	"regexp"
	"strings"
	"time"

	"os/exec"

	"lightbend.com/cloudflow/itest/cli"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
)

// Assumptions:
// In this test, we are going to deploy the test application to an existing cluster.
// For this first step, we assume that the cluster is correctly deployed and that kubectl is configured to talk to it.
// The application is already built
// We also assume that the test is going to be executed manually.
// Automation will be the next step

const (
	JsonTokenSrc    = "/keybase/team/assassins/gcloud/pipelines-serviceaccount-key-container-registry-read-write.json"
	ShortTimeout    = 60  // seconds
	LongTimeout     = 240 // seconds
	InitialWaitTime = "30s"
)

var swissKnifeApp = cli.App{
	Image: "eu.gcr.io/bubbly-observer-178213/swiss-knife:189-277e424",
	Name:  "swiss-knife",
}

var _ = Describe("Application deployment", func() {
	Context("the cluster is clean for testing", func() {
		It("should not have the test app", func() {
			err := ensureAppNotDeployed(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
		})
	})

	Context("when I deploy an application", func() {
		It("should start a deployment", func() {
			jsonToken := getToken()
			output, err := cli.Deploy(swissKnifeApp, jsonToken)
			Expect(err).NotTo(HaveOccurred())
			expected := "Deployment of application `" + swissKnifeApp.Name + "` has started."
			Expect(output).To(ContainSubstring(expected))
			waitTime, _ := time.ParseDuration(InitialWaitTime)
			time.Sleep(waitTime) // this wait is needed to let the application deploy
		})

		It("should be in the list of applications in the cluster", func() {
			list, err := cli.ListApps()
			Expect(err).NotTo(HaveOccurred())
			appNames := listAppNames(list)
			Expect(appNames).To(ContainElement(swissKnifeApp.Name))
		})

		It("should get to a 'running' status, eventually", func(done Done) {
			// TODO: check status flag once it's fixed
			status, err := checkStatusIs(swissKnifeApp, "Running")
			Expect(err).NotTo(HaveOccurred())
			Expect(status).To(Equal("Running"))
			close(done)
		}, LongTimeout)
	})

	Context("A deployed test application that uses akka, spark, and flink", func() {
		It("should contain a spark process", func() {
			status, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := cli.GetStreamlets(&status)
			Expect(streamlets).To(ContainElement("spark-process"))
		})

		It("should contain a flink process", func() {
			status, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := cli.GetStreamlets(&status)
			Expect(streamlets).To(ContainElement("flink-process"))
		})

		It("should contain an akka process", func() {
			status, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := cli.GetStreamlets(&status)
			Expect(streamlets).To(ContainElement("akka-process"))
		})

		It("should produce a counter in the raw output log", func(done Done) {
			pod, err := cli.GetOneOfThePodsForStreamlet(swissKnifeApp, "raw-egress")
			Expect(err).NotTo(HaveOccurred())
			_, err = checkLastLogsContains(pod, swissKnifeApp.Name, "count:")
			Expect(err).NotTo(HaveOccurred())
			close(done)
		}, LongTimeout)

		It("should produce a counter in the akka output log", func(done Done) {
			pod, err := cli.GetOneOfThePodsForStreamlet(swissKnifeApp, "akka-egress")
			Expect(err).NotTo(HaveOccurred())
			_, err = checkLastLogsContains(pod, swissKnifeApp.Name, "count:")
			Expect(err).NotTo(HaveOccurred())
			close(done)
		}, LongTimeout)

		It("should produce a counter in the spark output log", func(done Done) {
			pod, err := cli.GetOneOfThePodsForStreamlet(swissKnifeApp, "spark-egress")
			Expect(err).NotTo(HaveOccurred())
			_, err = checkLastLogsContains(pod, swissKnifeApp.Name, "count:")
			Expect(err).NotTo(HaveOccurred())
			close(done)
		}, LongTimeout)

		It("should produce a counter in the flink output log", func(done Done) {
			pod, err := cli.GetOneOfThePodsForStreamlet(swissKnifeApp, "flink-egress")
			Expect(err).NotTo(HaveOccurred())
			_, err = checkLastLogsContains(pod, swissKnifeApp.Name, "count:")
			Expect(err).NotTo(HaveOccurred())
			close(done)
		}, LongTimeout)

	})

	Context("A deployed application can be undeployed", func() {
		It("should undeploy the test app", func(done Done) {
			err := cli.Undeploy(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			err = expectFindApp(swissKnifeApp, false)
			Expect(err).NotTo(HaveOccurred())
			close(done)
		}, ShortTimeout)
	})
})

func expectFindApp(app cli.App, expected bool) error {
	for {
		apps, err := cli.ListApps()
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
		time.Sleep(time.Second)
	}
}

func checkLastLogsContains(pod string, namespace string, str string) (string, error) {
	for {
		logs, err := getLogs(pod, namespace, "1s")
		if err != nil {
			return "", err
		}
		lastLine := getLastNonEmptyLine(logs)

		if strings.Contains(lastLine, str) == true {
			println("Found match: " + lastLine)
			return lastLine, nil
		}
		time.Sleep(time.Second)
	}
}

func getToken() string {
	data, err := ioutil.ReadFile(JsonTokenSrc)
	if err != nil {
		Fail("Can't read credentials for test")
	}
	return string(data)
}

type podEntry struct {
	name     string
	ready    string
	status   string
	restarts string
	age      string
}

func listAppNames(apps []cli.AppEntry) []string {
	list := make([]string, len(apps))
	for _, entry := range apps {
		list = append(list, entry.Name)
	}
	return list
}

func ensureAppNotDeployed(app cli.App) error {
	apps, err := cli.ListApps()
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
	if found {
		if err := cli.Undeploy(app); err != nil {
			return err
		}
		return ensureNoPods(app)
	}
	return nil
}

func ensureNoPods(app cli.App) error {
	fmt.Printf("Ensuring no pods for app [%s]\n", app.Name)
	sleepDuration, err := time.ParseDuration("1s")
	if err != nil {
		return err
	}

	first := true
	var pods []podEntry

	for first || len(pods) > 0 {
		first = false
		time.Sleep(sleepDuration)
		pods, err = getPods(app)
		if err != nil {
			return err
		}
		fmt.Printf("...%d", len(pods))
	}
	return nil
}

func getPods(app cli.App) (pods []podEntry, err error) {
	cmd := exec.Command("kubectl", "get", "pods", "-n", app.Name)
	out, er := cmd.CombinedOutput()
	if er != nil {
		err = er
		return
	}
	str := string(out)
	splits := strings.Split(str, "\n")
	whitespaces := regexp.MustCompile(`\s+`)
	var res []podEntry
	for i, line := range splits {
		switch i {
		case 0:
			continue
		default:
			parts := whitespaces.Split(line, -1)
			if len(parts) == 5 {
				podEntry := podEntry{parts[0], parts[1], parts[2], parts[3], parts[4]}
				res = append(res, podEntry)
			}
		}
	}
	return res, nil
}

func checkStatusIs(app cli.App, status string) (res string, err error) {
	for {
		appStatus, er := cli.Status(app)
		if er != nil {
			err = er
			return
		}
		allSame := true
		for _, entry := range appStatus.StreamletPods {
			allSame = allSame && entry.Status == status
			if !allSame {
				break
			}
		}
		if allSame {
			return status, nil
		}
		time.Sleep(time.Second)
	}
}

func getLastNonEmptyLine(str string) string {
	lines := strings.Split(str, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if len(strings.TrimSpace(lines[i])) > 0 {
			return lines[i]
		}
	}
	return ""
}

func getLogs(pod string, namespace string, since string) (logs string, err error) {
	sinceParam := "--since=" + since
	cmd := exec.Command("kubectl", "logs", pod, "-n", namespace, sinceParam)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", err
	}
	return string(out), nil
}
