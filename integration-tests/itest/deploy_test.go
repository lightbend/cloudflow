package main_test

import (
	"log"
	"time"

	"github.com/lightbend/cloudflow/integration-test/itest/cli"
	"github.com/lightbend/cloudflow/integration-test/itest/kubectl"

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
	XLongTimeout    = 600 // seconds
	InitialWaitTime = "30s"
)

var swissKnifeApp = cli.App{
	Image: "docker.io/lightbend/swiss-knife:210-9478a19",
	Name:  "swiss-knife",
}

var _ = Describe("Application deployment", func() {
	Context("check that there's a cluster available with cloudflow installed", func() {
		It("should succeed to list apps", func() {
			_, err := cli.ListApps()
			if err != nil {
				log.Fatal("Error: Cluster not available or Cloudflow is not installed. Terminating tests.", err)
			}

		})
	})

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
			appNames, err := cli.ListAppNames()
			Expect(err).NotTo(HaveOccurred())
			Expect(appNames).To(ContainElement(swissKnifeApp.Name))
		})

		It("should get to a 'running' status, eventually", func(done Done) {

			// this wait is needed to let the application deploy and get to a stable state
			// a bug in the `cloudflow status` call.
			// see also: https://github.com/lightbend/cloudflow/issues/201
			waitTime, _ := time.ParseDuration(InitialWaitTime)
			time.Sleep(waitTime)

			status, err := cli.PollUntilPodsStatusIs(swissKnifeApp, "Running")

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
			_, err = kubectl.PollUntilLogsContains(pod, swissKnifeApp.Name, output)
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

	Context("A deployed streamlet can be scaled", func() {
		// A function that calculates the streamlet scale based on pod count
		type scalePodCorrection func(scale int) int
		var noCorrection scalePodCorrection = func(scale int) int { return scale }
		var coordinatorCorrection scalePodCorrection = func(scale int) int { return scale - 1 }
		scaleCheck := func(streamlet string, scalePodCorrection scalePodCorrection) {
			By("Determining initial scale factor")

			pods, err := cli.GetPodsForStreamlet(swissKnifeApp, streamlet)
			Expect(err).NotTo(HaveOccurred())
			initialPodCount := len(pods)
			initialScale := scalePodCorrection(initialPodCount)

			By("Issuing a +1 scale up")

			newScale := initialScale + 1
			expectedPodCount := initialPodCount + 1
			err = cli.Scale(swissKnifeApp, streamlet, newScale)
			Expect(err).NotTo(HaveOccurred())
			_, err = cli.PollUntilExpectedPodsForStreamlet(swissKnifeApp, streamlet, expectedPodCount)
			Expect(err).NotTo(HaveOccurred())

			By("Issuing a scale back to the original value")

			err = cli.Scale(swissKnifeApp, streamlet, initialScale)
			Expect(err).NotTo(HaveOccurred())
			_, err = cli.PollUntilExpectedPodsForStreamlet(swissKnifeApp, streamlet, initialPodCount)
			Expect(err).NotTo(HaveOccurred())
		}

		It("should scale an akka streamlet up and down", func(done Done) {
			scaleCheck("akka-process", noCorrection)
			close(done)
		}, LongTimeout)

		It("should scale a spark streamlet up and down", func(done Done) {
			scaleCheck("spark-process", coordinatorCorrection)
			close(done)
		}, LongTimeout)

		It("should scale a flink streamlet up and down", func(done Done) {
			scaleCheck("flink-process", coordinatorCorrection)
			close(done)
		}, XLongTimeout)
	})

	Context("A deployed application can be undeployed", func() {
		It("should undeploy the test app", func(done Done) {
			err := cli.Undeploy(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			err = cli.PollUntilAppPresenceIs(swissKnifeApp, false)
			Expect(err).NotTo(HaveOccurred())
			kubectl.WaitUntilNoPods(swissKnifeApp.Name)
			close(done)
		}, LongTimeout)
	})
})

// ensureAppNotDeployed verifies that the given app is not deployed.
// In case that the app is deployed in the cluster, it initiates an undeploy
// and waits until all pods from the app have been removed.
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
		return kubectl.WaitUntilNoPods(app.Name)
	}
	return nil
}
