package main_test

import (
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/lightbend/cloudflow/integration-test/itest/cli"
	"github.com/lightbend/cloudflow/integration-test/itest/k8s"
	"github.com/lightbend/cloudflow/integration-test/itest/k8s_pvc"
	"github.com/lightbend/cloudflow/integration-test/itest/k8s_secret"
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
	ShortTimeout                   = 60  // seconds
	LongTimeout                    = 240 // seconds
	XLongTimeout                   = 600 // seconds
	InitialWaitTime                = "30s"
	UpdateConfigParamsFile         = "./resources/update_config_params.conf"
	UpdateConfigPayload            = "payload: updated_config"
	UpdateAkkaProcessResourcesFile = "./resources/update_akka_process_resources.conf"
	UpdateAkkaRuntimeResourcesFile = "./resources/update_akka_runtime.conf"
	UpdateSparkConfigurationFile   = "./resources/update_spark_config.conf"
	UpdateMountingSecret           = "./resources/update_mounting_secret.conf"
	UpdateMountingPVC              = "./resources/update_mounting_pvc.conf"
	PVCResourceFile                = "./resources/pvc.yaml"
	PVCResourceName                = "myclaim"
	PVCResourceLocalFileMountPath  = "./resources/imhere.txt"
	PVCResourceAkkaFileMountPath   = "/tmp/some-akka/file.txt"
	PVCResourceSparkFileMountPath  = "/tmp/some-spark/file.txt"
	PVCResourceFlinkFileMountPath  = "/tmp/some-flink/file.txt"
	PVCResourceFileContent         = "hello"
	SecretResourceFile             = "./resources/secret.yaml"
	SecretResourceFileName         = "mysecret"
	SecretResourceFilePassword     = "1f2d1e2e67df"
	SecretResourceFileMountPath    = "/tmp/some/password"
	UpdateSparkConfigOutput        = "locality=[5s]"
	UpdateAkkaConfigurationFile    = "./resources/update_akka_config.conf"
	UpdateAkkaConfigOutput         = "log-dead-letters=[15]"
)

var deploySleepTime, _ = time.ParseDuration("5s")
var configureSleepTime, _ = time.ParseDuration("30s")

var swissKnifeApp = cli.App{
	CRFile: "./resources/swiss-knife.json",
	Name:   "swiss-knife",
}

var clientset = k8s.InitClient()

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
		It("should not have secrets remaining from previous runs of test app", func() {
			err := k8s_secret.DeleteSecrets(swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
		})
		It("should not have pvcs remaining from previous runs of test app", func() {
			err := k8s_pvc.DeletePVCs(swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
		})
	})

	Context("when I deploy an application", func() {
		It("should start a deployment", func() {
			output, err := cli.Deploy(swissKnifeApp, "", "")
			Expect(err).NotTo(HaveOccurred())
			expected := "Deployment of application `" + swissKnifeApp.Name + "` has started."
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			Expect(output).To(ContainSubstring(expected))
		})

		It("should be in the list of applications in the cluster", func() {
			appNames, err := cli.ListAppNames()
			Expect(err).NotTo(HaveOccurred())
			Expect(appNames).To(ContainElement(swissKnifeApp.Name))
		})

		It("should get to a 'running' status, eventually", func(done Done) {

			status, err := cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

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
		It("should produce a counter in the raw output log", func(done Done) {
			checkAnyPodLogForOutput("raw-egress", "count:")
			close(done)
		}, LongTimeout)

		It("should produce a counter in the akka output log", func(done Done) {
			checkAnyPodLogForOutput("akka-egress", "count:")
			close(done)
		}, LongTimeout)

		It("should produce a counter in the spark output log", func(done Done) {
			checkAnyPodLogForOutput("spark-egress", "count:")
			close(done)
		}, LongTimeout)

		It("should produce a counter in the flink output log", func(done Done) {
			checkAnyPodLogForOutput("flink-egress", "count:")
			close(done)
		}, LongTimeout)
	})

	Context("Configuration parameters of a deployed streamlet can be configured using the CLI", func() {
		It("should reconfigure the application", func(done Done) {
			err := cli.Configure(swissKnifeApp, UpdateConfigParamsFile)
			Expect(err).NotTo(HaveOccurred())

			By("Wait for the application to get configured")
			time.Sleep(configureSleepTime) // this wait is to let the application get configured
			close(done)
		}, LongTimeout)

		It("should get to a 'running' app status, eventually", func(done Done) {
			status, err := cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

			Expect(err).NotTo(HaveOccurred())
			Expect(status).To(Equal("Running"))
			close(done)
		}, LongTimeout)

		It("should have configured an akka streamlet", func(done Done) {
			checkAnyPodLogForOutput("akka-egress", UpdateConfigPayload)
			close(done)
		}, LongTimeout)

		It("should have configured a spark streamlet", func(done Done) {
			checkAnyPodLogForOutput("spark-egress", UpdateConfigPayload)
			close(done)
		}, LongTimeout)

		XIt("should have configured a flink streamlet", func(done Done) {
			checkAnyPodLogForOutput("flink-egress", UpdateConfigPayload)
			close(done)
		}, XLongTimeout)
	})

	Context("Application swiss-knife is deployed and running", func() {

		It("should deploy a secret", func() {
			_, err := k8s_secret.CreateSecret(SecretResourceFile, swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
		})

		It("should reconfigure akka streamlets to add a secret as mounting file", func(done Done) {
			err := cli.Configure(swissKnifeApp, UpdateMountingSecret)
			Expect(err).NotTo(HaveOccurred())
			By("Wait for the deployment of the new configuration")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")
			close(done)
		}, LongTimeout)

		It("should find specific content in the secret mounted file in any akka streamlet", func(done Done) {
			out, err := k8s.ReadFile(swissKnifeApp.Name, clientset, "akka", SecretResourceFileMountPath)
			Expect(err).NotTo(HaveOccurred())
			Expect(out).To(Equal(SecretResourceFilePassword))
			close(done)
		}, LongTimeout)

		It("should delete this secret", func() {
			err := k8s_secret.DeleteSecret(SecretResourceFileName, swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
		})
	})

	Context("Application swiss-knife is deployed and running", func() {

		It("should TRY reconfigure spark streamlets to add a pvc but find there's no pvc in the cluster", func(done Done) {
			err := cli.Configure(swissKnifeApp, UpdateMountingPVC)
			Expect(err).To(HaveOccurred())
			By("Wait for the deployment of the new configuration")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")
			close(done)
		}, LongTimeout)

		It("should deploy a pvc", func() {
			_, err := k8s_pvc.CreatePVC(PVCResourceFile, swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
		})

		It("should reconfigure streamlets to add a pvc and mount it", func(done Done) {
			err := cli.Configure(swissKnifeApp, UpdateMountingPVC)
			Expect(err).NotTo(HaveOccurred())
			By("Wait for the deployment of the new configuration")
			time.Sleep(configureSleepTime) // this wait is to let the application get configured

			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")
			close(done)
		}, LongTimeout)

		It("should write specific content in any streamlet", func(done Done) {
			err := k8s.CopyLocalFileToMatchingPod(swissKnifeApp.Name, clientset, "akka", PVCResourceLocalFileMountPath, PVCResourceAkkaFileMountPath)
			Expect(err).NotTo(HaveOccurred())
			close(done)
		}, LongTimeout)

		It("should find specific content in any spark streamlet", func(done Done) {
			out, err := k8s.ReadFile(swissKnifeApp.Name, clientset, "spark-process", PVCResourceSparkFileMountPath)
			Expect(err).NotTo(HaveOccurred())
			Expect(out).To(Equal(PVCResourceFileContent))
			close(done)
		}, LongTimeout)
		// Currently skipped b/c it has some issues
		XIt("should find specific content in any flink streamlet", func(done Done) {
			out, err := k8s.ReadFile(swissKnifeApp.Name, clientset, "flink-process", PVCResourceFlinkFileMountPath)
			Expect(err).NotTo(HaveOccurred())
			Expect(out).To(Equal(PVCResourceFileContent))
			close(done)
		}, LongTimeout)

		It("should delete that pvc", func() {
			err := k8s_pvc.DeletePVC(PVCResourceName, swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
		})

	})

	Context("Kubernetes configuration can be updated using the CLI", func() {
		It("should reconfigure the pods of an Akka application", func(done Done) {
			By("Register current CPU and memory for an Akka pods")
			appStatus, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			someAkkaPod := cli.GetFirstStreamletPod(&appStatus, "akka-process")
			Expect(someAkkaPod).NotTo(Equal(nil))
			podRes, err := kubectl.GetPodResources(swissKnifeApp.Name, someAkkaPod.Pod)
			Expect(err).NotTo(HaveOccurred())

			By("Reconfigure a single Akka streamlet")
			err = cli.Configure(swissKnifeApp, UpdateAkkaProcessResourcesFile)
			Expect(err).NotTo(HaveOccurred())

			By("Wait for the deployment of the new configuration")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

			By("Get new resource configuration")
			updatedAppStatus, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			updatedAkkaPod := cli.GetFirstStreamletPod(&updatedAppStatus, "akka-process")
			Expect(updatedAkkaPod).NotTo(Equal(nil))
			podUpdatedRes, err := kubectl.GetPodResources(swissKnifeApp.Name, updatedAkkaPod.Pod)
			Expect(podUpdatedRes.Cpu).NotTo(Equal(podRes.Cpu))
			Expect(podUpdatedRes.Mem).NotTo(Equal(podRes.Mem))
			// TODO: Read the config file and compare with the values there to avoid out-of-sync situations
			Expect(podUpdatedRes.Cpu).To(Equal("550m"))
			Expect(podUpdatedRes.Mem).To(Equal("612M"))
			close(done)
		}, LongTimeout)

		It("Should reconfigure the Akka runtime of the complete application", func(done Done) {
			By("Register the current CPU and memory for all Akka streamlets")
			appStatus, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())
			streamlets := [5]string{"akka-process", "akka-egress", "spark-egress", "raw-egress", "flink-egress"}
			streamletResourceConfigMap := make(map[string]kubectl.PodResources)
			for _, streamlet := range streamlets {
				pod := cli.GetFirstStreamletPod(&appStatus, streamlet)
				Expect(pod).NotTo(Equal(nil))
				podRes, err := kubectl.GetPodResources(swissKnifeApp.Name, pod.Pod)
				Expect(err).NotTo(HaveOccurred())
				streamletResourceConfigMap[streamlet] = podRes
			}

			By("Reconfigure the Akka Kubernetes Runtime")
			err = cli.Configure(swissKnifeApp, UpdateAkkaRuntimeResourcesFile)
			Expect(err).NotTo(HaveOccurred())

			By("Wait for the deployment of the new configuration")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

			By("Get new resource configuration")
			updatedAppStatus, err := cli.Status(swissKnifeApp)
			Expect(err).NotTo(HaveOccurred())

			for _, streamlet := range streamlets {
				pod := cli.GetFirstStreamletPod(&updatedAppStatus, streamlet)
				Expect(pod).NotTo(Equal(nil))
				updatedPodRes, err := kubectl.GetPodResources(swissKnifeApp.Name, pod.Pod)
				Expect(err).NotTo(HaveOccurred())
				oldRes := streamletResourceConfigMap[streamlet]
				Expect(updatedPodRes.Cpu).NotTo(Equal(oldRes.Cpu))
				Expect(updatedPodRes.Mem).NotTo(Equal(oldRes.Mem))
				// TODO: Read the config file for these values
				Expect(updatedPodRes.Cpu).To(Equal("665m"))
				Expect(updatedPodRes.Mem).To(Equal("655M"))
			}
			close(done)
		}, LongTimeout)
	})

	Context("Framework configuration can be updated using the CLI", func() {
		It("should reconfigure the configuration of a Spark application", func(done Done) {
			By("Reconfigure Spark-specific configuration")
			err := cli.Configure(swissKnifeApp, UpdateSparkConfigurationFile)
			Expect(err).NotTo(HaveOccurred())

			By("Wait for the deployment of the new configuration")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

			By("Verifying configuration update")
			checkMatchingPodLogForOutput("spark-config-output", "driver", UpdateSparkConfigOutput)
			close(done)
		}, LongTimeout)

		It("should reconfigure the configuration of an Akka application", func(done Done) {
			By("Reconfigure Akka-specific configuration")
			err := cli.Configure(swissKnifeApp, UpdateAkkaConfigurationFile)
			Expect(err).NotTo(HaveOccurred())

			By("Wait for the deployment of the new configuration")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

			By("Verifying configuration update")
			checkAnyPodLogForOutput("akka-config-output", UpdateAkkaConfigOutput)
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

			By("Wait for the deployment of the new scale factor")
			time.Sleep(deploySleepTime) // this wait is to let the application go into deployment
			cli.PollUntilAppStatusIs(swissKnifeApp, "Running")

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

		// Currently skipped b/c it takes too long!
		XIt("should scale a flink streamlet up and down", func(done Done) {
			scaleCheck("flink-process", coordinatorCorrection)
			close(done)
		}, XLongTimeout)
	})

	Context("A deployed application can be undeployed", func() {
		It("should delete test secrets and undeploy the test app", func(done Done) {
			err := k8s_secret.DeleteSecrets(swissKnifeApp.Name, clientset)
			Expect(err).NotTo(HaveOccurred())
			err = cli.Undeploy(swissKnifeApp)
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

func checkAnyPodLogForOutput(streamlet string, output string) {
	pod, err := cli.GetOneOfThePodsForStreamlet(swissKnifeApp, streamlet)
	Expect(err).NotTo(HaveOccurred())
	_, err = kubectl.PollUntilLogsContains(pod, swissKnifeApp.Name, output)
	Expect(err).NotTo(HaveOccurred())
}

func checkMatchingPodLogForOutput(streamlet string, partialPodName string, output string) {
	pods, err := cli.GetPodsForStreamlet(swissKnifeApp, streamlet)
	Expect(err).NotTo(HaveOccurred())
	var targetPod string
	for _, pod := range pods {
		if strings.Contains(pod, partialPodName) {
			targetPod = pod
			break
		}
	}
	if targetPod == "" {
		failStr := fmt.Sprintf("Could not find match for pod [%s] for streamlet [%s]", partialPodName, streamlet)
		Fail(failStr)
	}
	fmt.Printf("Going to monitor pod [%s] for streamlet [%s] \n", targetPod, streamlet)
	_, err = kubectl.PollUntilLogsContains(targetPod, swissKnifeApp.Name, output)
	Expect(err).NotTo(HaveOccurred())
	return
}
