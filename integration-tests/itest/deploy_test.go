package main_test

import (
	"fmt"
	"io/ioutil"
	"regexp"
	"strconv"
	"strings"
	"time"

	"log"
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
			output, err := deploy(swissKnifeApp, jsonToken)
			Expect(err).NotTo(HaveOccurred())
			expected := "Deployment of application `" + swissKnifeApp.name + "` has started."
			Expect(output).To(ContainSubstring(expected))
		})

		It("should be in the list of applications in the cluster", func() {
			list, err := listAppNames()
			Expect(err).NotTo(HaveOccurred())
			Expect(list).To(ContainElement(swissKnifeApp.name))
		})

		It("should get to a 'running' status, eventually", func(done Done) {
			// TODO: check status flag once it's fixed
			status, err := checkStatusIs(swissKnifeApp, "Running")
			Expect(err).NotTo(HaveOccurred())
			Expect(status).To(Equal("Running"))
			close(done)
		}, 10) // timeout in seconds
	})

	Context("A deployed application that uses akka, spark, and flink", func() {
		It("should contain a spark process", func() {
			status, err := getStatus(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := getStreamlets(status)
			Expect(streamlets).To(ContainElement("spark-process"))
		})

		It("should contain a flink process", func() {
			status, err := getStatus(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := getStreamlets(status)
			Expect(streamlets).To(ContainElement("flink-process"))
		})

		It("should contain an akka process", func() {
			status, err := getStatus(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := getStreamlets(status)
			Expect(streamlets).To(ContainElement("akka-process"))
		})

		It("should produce a counter in the raw output log", func() {
			logs, err := getLogs(swissKnifeApp, "raw-egress")
			Expect(err).NotTo(HaveOccurred())
			lastLine, err := getLastMinOneLine(logs)
			Expect(err).NotTo(HaveOccurred())
			Expect(lastLine).To(ContainSubstring("count:"))
		})

		It("should produce a counter in the raw output log", func() {
			err := checkCountInLog(swissKnifeApp, "raw-egress")
			Expect(err).NotTo(HaveOccurred())
		})

		It("should produce a counter in the akka output log", func() {
			err := checkCountInLog(swissKnifeApp, "akka-egress")
			Expect(err).NotTo(HaveOccurred())
		})

		It("should produce a counter in the spark output log", func() {
			err := checkCountInLog(swissKnifeApp, "spark-egress")
			Expect(err).NotTo(HaveOccurred())
		})

		It("should produce a counter in the flink output log", func() {
			err := checkCountInLog(swissKnifeApp, "flink-egress")
			Expect(err).NotTo(HaveOccurred())
		})
	})
})

func checkCountInLog(app app, streamlet string) error {
	logs, err := getLogs(swissKnifeApp, streamlet)
	if err != nil {
		return err
	}
	lastLine, err := getLastMinOneLine(logs)
	if err != nil {
		return err
	}
	Expect(lastLine).To(ContainSubstring("count:"))
	return nil
}

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

type podEntry struct {
	name     string
	ready    string
	status   string
	restarts string
	age      string
}

type streamletPod struct {
	streamlet string
	pod       string
	status    string
	restarts  int
	ready     bool
}

type appStatus struct {
	name          string
	namespace     string
	version       string
	created       string
	status        string
	streamletPods []streamletPod
}

// func status(app app) (status appStatus, err error )

func listAppNames() (entries []string, err error) {
	apps, er := cli.ListApps()
	if er != nil {
		err = er
		return
	}
	list := make([]string, len(apps))
	for _, entry := range apps {
		list = append(list, entry.Name)
	}
	return list, nil
}

func ensureAppNotDeployed(app app) error {
	apps, err := cli.ListApps()
	if err != nil {
		return err
	}
	found := false
	for _, entry := range apps {
		if entry.Name == app.name {
			found = true
			break
		}
	}
	if found {
		err := cli.Undeploy(app.name)
		if err != nil {
			return err
		}
		err = ensureNoPods(app)
		return err
	}
	return nil
}

func ensureNoPods(app app) error {
	fmt.Printf("Ensuring no pods for app [%s]\n", app.name)
	sleepDuration, err := time.ParseDuration("1s")
	if err != nil {
		log.Fatal("duration gives error", err)
		return err // pfff
	}
	time.Sleep(sleepDuration)
	pods, err := getPods(app)
	if err != nil {
		log.Fatal("get Pods gives error", err)
		return err // pfff^2
	}
	fmt.Printf("Initial pod count %d\n", len(pods))

	for len(pods) > 0 {

		time.Sleep(sleepDuration)
		pods, err = getPods(app)
		if err != nil {
			return err
		}
		fmt.Printf("...%d", len(pods))
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

func checkStatusIs(app app, status string) (res string, err error) {
	for {
		appStatus, er := getStatus(app)
		if er != nil {
			err = er
			return
		}
		allSame := true
		for _, entry := range appStatus.streamletPods {
			allSame = allSame && entry.status == status
			if !allSame {
				fmt.Printf("Entry [%s, %s] is not compliant with status [%s]", entry.streamlet, entry.status, status)
				break
			}
		}
		if allSame {
			return status, nil
		}
		time.Sleep(time.Second)
	}
}

func getStreamlets(appStatus appStatus) []string {
	var res []string
	for _, entry := range appStatus.streamletPods {
		res = append(res, entry.streamlet)
	}
	return res
}

func getStatus(app app) (status appStatus, err error) {
	cmd := exec.Command("kubectl", "cloudflow", "status", app.name)
	out, er := cmd.CombinedOutput()
	if er != nil {
		err = er
		return
	}
	str := string(out)
	splits := strings.Split(str, "\n")

	names := [5]string{"Name:", "Namespace:", "Version:", "Created:", "Status:"}
	fields := [5]*string{&status.name, &status.namespace, &status.version, &status.created, &status.status}
	var streamletPods []streamletPod

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
			var streamletPod streamletPod
			streamletPod.streamlet = parts[0]
			streamletPod.pod = parts[1]
			streamletPod.status = parts[2]
			restarts, er := strconv.Atoi(parts[3])
			if er != nil {
				err = er
				return
			}
			streamletPod.restarts = restarts
			ready, er := strconv.ParseBool(parts[4])
			if er != nil {
				err = er
				return
			}
			streamletPod.ready = ready
			streamletPods = append(streamletPods, streamletPod)
		}
	}
	status.streamletPods = streamletPods
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
	} else {
		err = fmt.Errorf("string didn't contain [%d] separate words: [%s]", segments, str)
		return
	}
}

func getStreamletPod(status *appStatus, streamlet string) *streamletPod {
	for _, entry := range status.streamletPods {
		if entry.streamlet == streamlet {
			return &entry
		}
	}
	return nil
}

func getLastMinOneLine(str string) (line string, err error) {
	lines := strings.Split(str, "\n")
	if len(lines) < 2 {
		err = fmt.Errorf("input had too few lines")
		return
	}
	return lines[len(lines)-2], nil
}

func getLogs(app app, streamlet string) (logs string, err error) {
	status, er := getStatus(app)
	if er != nil {
		err = er
		return
	}
	streamletPod := getStreamletPod(&status, streamlet)
	if streamletPod == nil {
		err = fmt.Errorf("could not find entry for streamlet [%s]", streamlet)
	}
	cmd := exec.Command("kubectl", "logs", streamletPod.pod, "-n", app.name)
	out, er := cmd.CombinedOutput()
	if er != nil {
		err = er
		return
	}
	return string(out), nil

}
