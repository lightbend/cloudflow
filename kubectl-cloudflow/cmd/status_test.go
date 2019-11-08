package cmd

import (
	"encoding/json"
	"testing"

	"github.com/lightbend/cloudflow/kubectl-cloudflow/domain"
	"github.com/stretchr/testify/assert"
)

func Test_calcAppStatus_PendingMissing(t *testing.T) {
	streamletStatuses := []domain.StreamletStatus{
		{
			StreamletName: "valid-logger",
			PodStatuses: []domain.PodStatus{
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
	streamletStatuses := []domain.StreamletStatus{
		{
			StreamletName: "ingress",
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
	streamletStatuses := []domain.StreamletStatus{
		{
			StreamletName: "valid-logger",
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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
	streamletStatuses := []domain.StreamletStatus{
		{
			StreamletName: "invalid-logger",
			PodStatuses: []domain.PodStatus{
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
			PodStatuses: []domain.PodStatus{
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

func createTestCloudflowApplication(t *testing.T, streamletStatuses []domain.StreamletStatus) domain.CloudflowApplication {
	applicationConfiguration := domain.TestApplicationDescriptor()
	var spec domain.CloudflowApplicationSpec
	jsonError := json.Unmarshal([]byte(applicationConfiguration), &spec)
	assert.Empty(t, jsonError)
	app := domain.NewCloudflowApplication(spec)

	status := domain.CloudflowApplicationStatus{
		AppID:             spec.AppID,
		AppVersion:        spec.AppVersion,
		EndpointStatuses:  []domain.EndpointStatus{},
		StreamletStatuses: streamletStatuses,
	}
	app.Status = status
	return app
}
