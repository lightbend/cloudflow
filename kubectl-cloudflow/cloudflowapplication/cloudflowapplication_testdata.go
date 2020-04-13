package cloudflowapplication

// TestApplicationDescriptor returns a CloudflowApplication descriptor as a string
func TestApplicationDescriptor() string {

	return `
		{
		  "agent_paths": {
		    "Cinnamon": "/app/cinnamon/cinnamon-agent-2.12.0.jar",
		    "prometheus": "/app/prometheus/jmx_prometheus_javaagent-0.11.0.jar"
		  },
		  "app_id": "sensor-data-scala",
		  "connections": [
		    {
		      "inlet_name": "in",
		      "inlet_streamlet_name": "valid-logger",
		      "outlet_name": "valid",
		      "outlet_streamlet_name": "validation"
		    },
		    {
		      "inlet_name": "in",
		      "inlet_streamlet_name": "rotorizer",
		      "outlet_name": "valid",
		      "outlet_streamlet_name": "validation"
		    },
		    {
		      "inlet_name": "in",
		      "inlet_streamlet_name": "rotor-avg-logger",
		      "outlet_name": "out",
		      "outlet_streamlet_name": "rotorizer"
		    },
		    {
		      "inlet_name": "in",
		      "inlet_streamlet_name": "invalid-logger",
		      "outlet_name": "invalid",
		      "outlet_streamlet_name": "validation"
		    },
		    {
		      "inlet_name": "in",
		      "inlet_streamlet_name": "metrics",
		      "outlet_name": "out",
		      "outlet_streamlet_name": "merge"
		    },
		    {
		      "inlet_name": "in-0",
		      "inlet_streamlet_name": "merge",
		      "outlet_name": "out",
		      "outlet_streamlet_name": "http-ingress"
		    },
		    {
		      "inlet_name": "in",
		      "inlet_streamlet_name": "validation",
		      "outlet_name": "out",
		      "outlet_streamlet_name": "metrics"
		    },
		    {
		      "inlet_name": "in-1",
		      "inlet_streamlet_name": "merge",
		      "outlet_name": "out",
		      "outlet_streamlet_name": "file-ingress"
		    }
		  ],
		  "deployments": [
		    {
		      "class_name": "cloudflow.examples.sensordata.InvalidMetricLogger",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.invalid-logger",
		      "port_mappings": {
		        "in": {
		          "app_id": "sensor-data-scala",
		          "outlet": "invalid",
		          "streamlet": "validation"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "invalid-logger",
		      "streamlet_name": "invalid-logger"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.MetricsValidation",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.validation",
		      "port_mappings": {
		        "invalid": {
		          "app_id": "sensor-data-scala",
		          "outlet": "invalid",
		          "streamlet": "validation"
		        },
		        "valid": {
		          "app_id": "sensor-data-scala",
		          "outlet": "valid",
		          "streamlet": "validation"
		        },
		        "in": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "metrics"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "validation",
		      "streamlet_name": "validation"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.SensorDataFileIngress",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.file-ingress",
		      "port_mappings": {
		        "out": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "file-ingress"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "file-ingress",
		      "streamlet_name": "file-ingress",
		      "volume_mounts": [
		        {
		          "access_mode": "ReadWriteMany",
		          "name": "source-data-mount",
		          "path": "/mnt/data",
		          "pvc_name": ""
		        }
		      ]
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.SensorDataMerge",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.merge",
		      "port_mappings": {
		        "out": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "merge"
		        },
		        "in-0": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "http-ingress"
		        },
		        "in-1": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "file-ingress"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "merge",
		      "streamlet_name": "merge"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.SensorDataHttpIngress",
		      "config": {
		        "cloudflow": {
		          "internal": {
		            "server": {
		              "container-port": 3004
		            }
		          }
		        }
		      },
		      "endpoint": {
		        "app_id": "sensor-data-scala",
		        "container_port": 3004,
		        "streamlet": "http-ingress"
		      },
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.http-ingress",
		      "port_mappings": {
		        "out": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "http-ingress"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "http-ingress",
		      "streamlet_name": "http-ingress"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.RotorspeedWindowLogger",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.rotor-avg-logger",
		      "port_mappings": {
		        "in": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "rotorizer"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "rotor-avg-logger",
		      "streamlet_name": "rotor-avg-logger"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.SensorDataToMetrics",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.metrics",
		      "port_mappings": {
		        "out": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "metrics"
		        },
		        "in": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "merge"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "metrics",
		      "streamlet_name": "metrics"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.ValidMetricLogger",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.valid-logger",
		      "port_mappings": {
		        "in": {
		          "app_id": "sensor-data-scala",
		          "outlet": "valid",
		          "streamlet": "validation"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "valid-logger",
		      "streamlet_name": "valid-logger"
		    },
		    {
		      "class_name": "cloudflow.examples.sensordata.RotorSpeedFilter",
		      "config": {},
		      "image": "sensor-data-scala:478-c0bd57f",
		      "name": "sensor-data-scala.rotorizer",
		      "port_mappings": {
		        "out": {
		          "app_id": "sensor-data-scala",
		          "outlet": "out",
		          "streamlet": "rotorizer"
		        },
		        "in": {
		          "app_id": "sensor-data-scala",
		          "outlet": "valid",
		          "streamlet": "validation"
		        }
		      },
		      "runtime": "akka",
		      "secret_name": "rotorizer",
		      "streamlet_name": "rotorizer"
		    }
		  ],
		  "library_version": "1.2.0",
		  "streamlets": [
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.InvalidMetricLogger",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in",
		            "schema": {
		              "fingerprint": "P00TowppyV4/jkAtSvVT0bS1KJGjz6bnJtQ3kpy6BsA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.InvalidMetric",
		              "schema": "{\"type\":\"record\",\"name\":\"InvalidMetric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"metric\",\"type\":{\"type\":\"record\",\"name\":\"Metric\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}},{\"name\":\"error\",\"type\":\"string\"}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "invalid-logger"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.MetricsValidation",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [
		          {
		            "name": "invalid",
		            "schema": {
		              "fingerprint": "P00TowppyV4/jkAtSvVT0bS1KJGjz6bnJtQ3kpy6BsA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.InvalidMetric",
		              "schema": "{\"type\":\"record\",\"name\":\"InvalidMetric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"metric\",\"type\":{\"type\":\"record\",\"name\":\"Metric\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}},{\"name\":\"error\",\"type\":\"string\"}]}"
		            }
		          },
		          {
		            "name": "valid",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "validation"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.SensorDataFileIngress",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [],
		        "labels": [],
		        "outlets": [
		          {
		            "name": "out",
		            "schema": {
		              "fingerprint": "n9jXvhy0zeq1aeiFrfiLdKoTYhWhci4K3D0cLHTHmIA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.SensorData",
		              "schema": "{\"type\":\"record\",\"name\":\"SensorData\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"measurements\",\"type\":{\"type\":\"record\",\"name\":\"Measurements\",\"fields\":[{\"name\":\"power\",\"type\":\"double\"},{\"name\":\"rotorSpeed\",\"type\":\"double\"},{\"name\":\"windSpeed\",\"type\":\"double\"}]}}]}"
		            }
		          }
		        ],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": [
		          {
		            "access_mode": "ReadWriteMany",
		            "name": "source-data-mount",
		            "path": "/mnt/data",
		            "pvc_name": ""
		          }
		        ]
		      },
		      "name": "file-ingress"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.SensorDataMerge",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in-0",
		            "schema": {
		              "fingerprint": "n9jXvhy0zeq1aeiFrfiLdKoTYhWhci4K3D0cLHTHmIA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.SensorData",
		              "schema": "{\"type\":\"record\",\"name\":\"SensorData\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"measurements\",\"type\":{\"type\":\"record\",\"name\":\"Measurements\",\"fields\":[{\"name\":\"power\",\"type\":\"double\"},{\"name\":\"rotorSpeed\",\"type\":\"double\"},{\"name\":\"windSpeed\",\"type\":\"double\"}]}}]}"
		            }
		          },
		          {
		            "name": "in-1",
		            "schema": {
		              "fingerprint": "n9jXvhy0zeq1aeiFrfiLdKoTYhWhci4K3D0cLHTHmIA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.SensorData",
		              "schema": "{\"type\":\"record\",\"name\":\"SensorData\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"measurements\",\"type\":{\"type\":\"record\",\"name\":\"Measurements\",\"fields\":[{\"name\":\"power\",\"type\":\"double\"},{\"name\":\"rotorSpeed\",\"type\":\"double\"},{\"name\":\"windSpeed\",\"type\":\"double\"}]}}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [
		          {
		            "name": "out",
		            "schema": {
		              "fingerprint": "n9jXvhy0zeq1aeiFrfiLdKoTYhWhci4K3D0cLHTHmIA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.SensorData",
		              "schema": "{\"type\":\"record\",\"name\":\"SensorData\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"measurements\",\"type\":{\"type\":\"record\",\"name\":\"Measurements\",\"fields\":[{\"name\":\"power\",\"type\":\"double\"},{\"name\":\"rotorSpeed\",\"type\":\"double\"},{\"name\":\"windSpeed\",\"type\":\"double\"}]}}]}"
		            }
		          }
		        ],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "merge"
		    },
		    {
		      "descriptor": {
		        "attributes": [
		          {
		            "attribute_name": "server",
		            "config_path": "cloudflow.internal.server.container-port"
		          }
		        ],
		        "class_name": "cloudflow.examples.sensordata.SensorDataHttpIngress",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [],
		        "labels": [],
		        "outlets": [
		          {
		            "name": "out",
		            "schema": {
		              "fingerprint": "n9jXvhy0zeq1aeiFrfiLdKoTYhWhci4K3D0cLHTHmIA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.SensorData",
		              "schema": "{\"type\":\"record\",\"name\":\"SensorData\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"measurements\",\"type\":{\"type\":\"record\",\"name\":\"Measurements\",\"fields\":[{\"name\":\"power\",\"type\":\"double\"},{\"name\":\"rotorSpeed\",\"type\":\"double\"},{\"name\":\"windSpeed\",\"type\":\"double\"}]}}]}"
		            }
		          }
		        ],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "http-ingress"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.RotorspeedWindowLogger",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "rotor-avg-logger"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.SensorDataToMetrics",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in",
		            "schema": {
		              "fingerprint": "n9jXvhy0zeq1aeiFrfiLdKoTYhWhci4K3D0cLHTHmIA=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.SensorData",
		              "schema": "{\"type\":\"record\",\"name\":\"SensorData\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"measurements\",\"type\":{\"type\":\"record\",\"name\":\"Measurements\",\"fields\":[{\"name\":\"power\",\"type\":\"double\"},{\"name\":\"rotorSpeed\",\"type\":\"double\"},{\"name\":\"windSpeed\",\"type\":\"double\"}]}}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [
		          {
		            "name": "out",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "metrics"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.ValidMetricLogger",
		        "config_parameters": [
		          {
		            "default_value": "debug",
		            "description": "Provide one of the following log levels, debug, info, warning or error",
		            "key": "log-level",
		            "validation_pattern": "^debug|info|warning|error$",
		            "validation_type": "string"
		          },
		          {
		            "default_value": "valid-logger",
		            "description": "Provide a prefix for the log lines",
		            "key": "msg-prefix",
		            "validation_pattern": ".*",
		            "validation_type": "string"
		          }
		        ],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "valid-logger"
		    },
		    {
		      "descriptor": {
		        "attributes": [],
		        "class_name": "cloudflow.examples.sensordata.RotorSpeedFilter",
		        "config_parameters": [],
		        "description": "",
		        "image": "sensor-data-scala:478-c0bd57f",
		        "inlets": [
		          {
		            "name": "in",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "labels": [],
		        "outlets": [
		          {
		            "name": "out",
		            "schema": {
		              "fingerprint": "4FsAt3XRP4YM7E9o8UOr1JGvSR79nGrt0wkSyWOKRUU=",
		              "format": "avro",
		              "name": "cloudflow.examples.sensordata.Metric",
		              "schema": "{\"type\":\"record\",\"name\":\"Metric\",\"namespace\":\"cloudflow.examples.sensordata\",\"fields\":[{\"name\":\"deviceId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},{\"name\":\"timestamp\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}"
		            }
		          }
		        ],
		        "project_id": "sensor-data-scala",
		        "runtime": "akka",
		        "volume_mounts": []
		      },
		      "name": "rotorizer"
		    }
		  ],
		  "version": "1"
		}`
}
