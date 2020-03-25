package main_test

import (
	"fmt"
	"time"

	"lightbend.com/cloudflow/itest/cli"
	"lightbend.com/cloudflow/itest/kctl"

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
	ShortTimeout    = 60  // seconds
	LongTimeout     = 240 // seconds
	InitialWaitTime = "30s"
)

var swissKnifeApp = cli.App{
	Image: "docker.io/lightbend/swiss-knife:210-9478a19",
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
			output, err := cli.Deploy(swissKnifeApp, "", "")
			Expect(err).NotTo(HaveOccurred())
			expected := "Deployment of application `" + swissKnifeApp.Name + "` has started."
			Expect(output).To(ContainSubstring(expected))
		})

		It("should be in the list of applications in the cluster", func() {
			list, err := cli.ListApps()
			Expect(err).NotTo(HaveOccurred())
			appNames := listAppNames(list)
			Expect(appNames).To(ContainElement(swissKnifeApp.Name))
		})

		It("should get to a 'running' status, eventually", func(done Done) {

			// this wait is needed to let the application deploy and get to a stable state
			// a bug in the `cloudflow status` call.
			// see also: https://github.com/lightbend/cloudflow/issues/201
			waitTime, _ := time.ParseDuration(InitialWaitTime)
			time.Sleep(waitTime)

			status, err := pollUntilPodsStatusIs(swissKnifeApp, "Running")

			Expect(err).NotTo(HaveOccurred())
			Expect(status).To(Equal("Running"))
			close(done)
		}, LongTimeout)
	})

	Context("The status of a deployed test application that uses akka, spark, and flink", func() {
		checkContainsStreamlet := func(streamlet string) {
			status, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := cli.GetStreamlets(&status)
			Expect(streamlets).To(ContainElement(streamlet))
		}
		It("should contain a spark process", func() {
			checkContainsStreamlet("spark-process")
		})

		It("should contain a flink process", func() {
			checkContainsStreamlet("flink-process")
		})

		It("should contain an akka process", func() {
			checkContainsStreamlet("akka-process")
		})
	})

	Context("Running streamlets from the sample app should produce counter data", func() {
		checkLogsForOutput := func(streamlet string, output string) {
			pod, err := cli.GetOneOfThePodsForStreamlet(swissKnifeApp, streamlet)
			Expect(err).NotTo(HaveOccurred())
			_, err = kctl.PollUntilLogsContains(pod, swissKnifeApp.Name, output)
			Expect(err).NotTo(HaveOccurred())
		}
		It("should produce a counter in the raw output log", func(done Done) {
			checkLogsForOutput("raw-egress", "count:")
			close(done)
		}, LongTimeout)

		It("should produce a counter in the akka output log", func(done Done) {
			checkLogsForOutput("akka-egress", "count:")
			close(done)
		}, LongTimeout)

		It("should produce a counter in the spark output log", func(done Done) {
			checkLogsForOutput("spark-egress", "count:")
			close(done)
		}, LongTimeout)

		It("should produce a counter in the flink output log", func(done Done) {
			checkLogsForOutput("flink-egress", "count:")
			close(done)
		}, LongTimeout)
	})

	Context("A deployed application can be undeployed", func() {
		It("should undeploy the test app", func(done Done) {
			err := cli.Undeploy(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			err = PollUntilAppPresenceIs(swissKnifeApp, false)
			Expect(err).NotTo(HaveOccurred())
			ensureNoPods(swissKnifeApp)
			close(done)
		}, LongTimeout)
	})
})

// PollUntilAppPresenceIs polls the list API for the presence of the app.
// The end condition depends on the `expected` flag
// If `expected` is true, this method will poll until the app is found (present)
// If `expected` is false, this method will poll until the app is not found (absent)
func PollUntilAppPresenceIs(app cli.App, expected bool) error {
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
	var pods []kctl.PodEntry

	for first || len(pods) > 0 {
		first = false
		time.Sleep(sleepDuration)
		pods, err = kctl.GetPods(app.Name)
		if err != nil {
			return err
		}
		fmt.Printf("...%d", len(pods))
	}
	return nil
}

// pollUntilPodsStatusIs polls the status of each pod of the application to be the expected status
// returns when all pods have the expected status.
func pollUntilPodsStatusIs(app cli.App, expected string) (res string, err error) {
	for {
		appStatus, er := cli.Status(app)
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
		time.Sleep(time.Second)
	}
}
