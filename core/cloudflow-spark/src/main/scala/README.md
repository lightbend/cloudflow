# Spark Support for Cloudflow

This module implements the support for 'Sparklets' or Spark-enabled Streamlets.

### Testing Notes

The Spark base streamlet tests, such as `SparkProcessor` require an external test instance of Kafka for execution.
The recommended way is to use _Docker_ to run Kafka.

With the running Kafka instance in place, update the test configuration with the IP address of the Kafka broker and enable
the test by replacing `ignore` to `in` in the test spec.

Then proceed to run the tests as usual.

