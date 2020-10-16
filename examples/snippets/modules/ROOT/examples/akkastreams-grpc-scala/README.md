Deploying a gRPC service as part of a CloudFlow app.

Testing locally:
* `sbt runLocal`
* `grpcurl -plaintext -d '{"payload":"foo-bar"}' localhost:3000 sensordata.SensorDataService.Provide`
