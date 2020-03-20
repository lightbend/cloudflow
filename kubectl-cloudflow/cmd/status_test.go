package cmd

import (
	"encoding/json"
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/cloudflowapplication"
	"github.com/stretchr/testify/assert"
)

func Test_calcAppStatus_PendingMissing(t *testing.T) {
	streamletStatuses := []cloudflowapplication.StreamletStatus{
		{
			StreamletName: "valid-logger",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "valid-logger-pod",
					Ready:    "False",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
	}

	app := createTestCloudflowApplication(t, streamletStatuses)
	appStatus := calcAppStatus(&app)
	assert.Equal(t, "Pending", appStatus)
}

func Test_calcAppStatus_PendingNotReady(t *testing.T) {
	streamletStatuses := []cloudflowapplication.StreamletStatus{
		{
			StreamletName: "ingress",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "ingress-pod",
					Ready:    "False",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "egress",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "egress-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
	}
	app := createTestCloudflowApplication(t, streamletStatuses)
	appStatus := calcAppStatus(&app)
	assert.Equal(t, "Pending", appStatus)
}

func Test_calcAppStatus_Running(t *testing.T) {
	streamletStatuses := []cloudflowapplication.StreamletStatus{
		{
			StreamletName: "valid-logger",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "valid-logger-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "validation",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "validation-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "sensor-data",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "sensor-data-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "rotor-avg-logger",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "rotor-avg-logger-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "metrics",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "metrics-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "invalid-logger",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "invalid-logger-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "rotorizer",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "rotorizer-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "merge",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "merge-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "file-ingress",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "file-ingress-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
	}

	app := createTestCloudflowApplication(t, streamletStatuses)
	appStatus := calcAppStatus(&app)
	assert.Equal(t, "Running", appStatus)
}

func Test_calcAppStatus_Crashing(t *testing.T) {
	streamletStatuses := []cloudflowapplication.StreamletStatus{
		{
			StreamletName: "invalid-logger",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "invalid-logger-pod",
					Ready:    "True",
					Restarts: 0,
					Status:   "Running",
				},
			},
		},
		{
			StreamletName: "validation",
			PodStatuses: []cloudflowapplication.PodStatus{
				{
					Name:     "validation-pod",
					Ready:    "False",
					Restarts: 3,
					Status:   "CrashLoopBackOff",
				},
			},
		},
	}

	app := createTestCloudflowApplication(t, streamletStatuses)
	appStatus := calcAppStatus(&app)
	assert.Equal(t, "CrashLoopBackOff", appStatus)
}

func createTestCloudflowApplication(t *testing.T, streamletStatuses []cloudflowapplication.StreamletStatus) cloudflowapplication.CloudflowApplication {
	applicationConfiguration := cloudflowapplication.TestApplicationDescriptor()
	var spec cloudflowapplication.CloudflowApplicationSpec
	jsonError := json.Unmarshal([]byte(applicationConfiguration), &spec)
	assert.Empty(t, jsonError)
	app := cloudflowapplication.NewCloudflowApplication(spec)

	status := cloudflowapplication.CloudflowApplicationStatus{
		AppID:             spec.AppID,
		AppVersion:        spec.AppVersion,
		EndpointStatuses:  []cloudflowapplication.EndpointStatus{},
		StreamletStatuses: streamletStatuses,
	}
	app.Status = status
	return app
}
