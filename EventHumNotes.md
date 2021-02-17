
# Notes for using Event Hub as the Kafka provider in Cloudflow

when deploying cloudflow using the helm charts make sure to configure appropriately default bootstrapServers an partitions. e.g.:

```
--set kafkaClusters.default.bootstrapServers=NAMESPACENAME.servicebus.windows.net:9093
--set kafkaClusters.default.partitions=1 # 1 to 32 can be increased up to 40 with request
```

---
Each Event Hub namespace can have maximum 10 hubs, this is an hard limit:
https://feedback.azure.com/forums/911458-event-hubs/suggestions/36989824-allow-more-than-10-hubs-per-event-hub-namespace

Each Kafka topic is a separate hub, is pretty easy to reach this limit in a reasonably complex Cloudflow application.
You can always workaround the issue using multiple namespaces and configuring appropriately the streamlets.

---
The minimum plan for having Kafka compatibilty in Event Hub is "Standard", e.g. the "Basic" plan is not working.

---
To deploy an application you need to appropriately configure the topics and inject the credentials, I have tested using a Shared Access Signature ( https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-for-kafka-ecosystem-overview#shared-access-signature-sas ) but I expect OAuth 2.0 to work as well.

This additional configuration needs be provided to each and every `kubectl cloudflow` `deploy`/`configure` command using the `--conf` option e.g.:

```
kubectl cloudflow deploy /target/swiss-knife.json --conf event-hub-config.conf  --no-registry-credentials
```

In this case `event-hub-config.conf` looks like (more details in https://cloudflow.io/docs/current/develop/blueprints.html#advanced-config):

```json
cloudflow {
  topics {
    source-data-pipe = ${shared-config}  {
      producers = [ingress.out]
      consumers = [raw-egress.in, akka-process.in, spark-process.in, flink-process.in, spark-config-output.in, akka-config-output.in]
    }

    akka-pipe = ${shared-config}  {
      producers = [akka-process.out]
      consumers = [akka-egress.in]
    }

    spark-pipe = ${shared-config}  {
      producers = [spark-process.out]
      consumers = [spark-egress.in]
    }

    flink-pipe = ${shared-config}  {
      producers = [flink-process.out]
      consumers = [flink-egress.in]
    }
  }
}

shared-config {
  connection-config = ${kafka-connection-config}
}

kafka-connection-config {
  bootstrap.servers="NAMESPACE.servicebus.windows.net:9093"

  security.protocol=SASL_SSL
  sasl.mechanism=PLAIN
  sasl.jaas.config="""org.apache.kafka.common.security.plain.PlainLoginModule required username="$ConnectionString" password="{YOUR.EVENTHUBS.CONNECTION.STRING}";"""
}
```

You can get `YOUR.EVENTHUBS.CONNECTION.STRING` through the UI by selecting:
Event Hubs -> Your Event Hub Namespace -> SharedAccessPolicies -> Select one or create -> copy the entire content of "Connection string–primary key"
