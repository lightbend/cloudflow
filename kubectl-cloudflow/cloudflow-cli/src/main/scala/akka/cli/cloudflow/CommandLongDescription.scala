/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

object CommandLongDescription {

  val deploy =
    """
      |Deploys a Cloudflow application to the cluster.
      |
      |Configuration files in HOCON format can be passed through with the --conf flag.
      |Configuration files are merged by concatenating the files passed with --conf flags.
      |The last --conf [file] argument can override values specified in earlier --conf [file] arguments.
      |In the example below, where the same configuration path is used in file1.conf and file2.conf,
      |the configuration value in file2.conf takes precedence, overriding the value provided by file1.conf:
      |
      |kubectl cloudflow deploy swiss-knife.json --conf file1.conf --conf file2.conf
      |
      |It is also possible to pass configuration values as command line arguments, as [config-key]=value pairs separated by
      |a space. The [config-key] must be an absolute path to the value, exactly how it would be defined in a config file.
      |Some examples:
      |
      |kubectl cloudflow deploy target/swiss-knife.json cloudflow.runtimes.spark.config.spark.driver.memoryOverhead=512
      |kubectl cloudflow deploy target/swiss-knife.json cloudflow.streamlets.spark-process.config-parameters.configurable-message="SPARK-OUTPUT:"
      |
      |
      |The arguments passed with '[config-key]=[value]' pairs take precedence over the files passed through with the '--conf' flag.
      |
      |The command supports a flag --scale to specify the scale of each streamlet on deploy in the form of key/value
      |pairs ('streamlet-name=scale') separated by comma.
      |  kubectl-cloudflow deploy call-record-aggregator.json --scale cdr-aggregator=3,cdr-generator1=3
      |
      |Streamlet volume mounts can be configured using the --volume-mount flag.
      |The flag accepts one or more key/value pair where the key is the name of the
      |volume mount, specified as '[streamlet-name].[volume-mount-name]', and the value
      |is the name of a Kubernetes Persistent Volume Claim, which needs to be located
      |in the same namespace as the Cloudflow application, e.g. the namespace with the
      |same name as the application.
      |
      |  kubectl cloudflow deploy call-record-aggregator.json --volume-mount my-streamlet.mount=pvc-name
      |
      |It is also possible to specify more than one "volume-mount" parameter.
      |
      |  kubectl cloudflow deploy call-record-aggregator.json --volume-mount my-streamlet.mount=pvc-name --volume-mount my-other-streamlet.mount=pvc-name
      |
      |You can optionally provide credentials for the docker registry that hosts the
      |images of the application by using the --username flag in combination with either
      |the --password-stdin or the --password flag.
      |
      |If no credentials are needed, for example, if the cluster already has credentials configured or if the registry does not require authentication to
      |pull an image, use the '--no-registry-credentials' flag.
      |
      |The --password-stdin flag is preferred because it is read from stdin, which
      |means that the password does not end up in the history of your shell.
      |One way to provide the password via stdin is to pipe it from a file:
      |
      |  cat key.json | kubectl cloudflow deploy call-record-aggregator.json --username _json_key --password-stdin
      |
      |You can also use --password, which is less secure:
      |
      |  kubectl cloudflow deploy call-record-aggregator.json --username _json_key -password "$(cat key.json)"
      |
      |If you do not provide a username and password, you will be prompted for them
      |the first time you deploy an image from a certain docker registry. The
      |credentials will be stored in a Kubernetes "image pull secret" and linked to
      |the Cloudflow service account. Subsequent usage of the deploy command will use
      |the stored credentials.
      |
      |You can update the credentials with the "update-docker-credentials" command.
      |
      |If your application contains streamlets that connect to each other through
      |Kafka then validation will check that all named Kafka cluster configurations
      |exist in the cloudflow namespace. If named Kafka cluster configurations
      |are not defined, but port mappings exist, then validation will check that a
      |default Kafka cluster configuration exists. Validation failure will result
      |in an error and the application will not be deployed.
      |""".stripMargin

  val configure =
    """kubectl cloudflow configure my-app --conf my-config.conf
      |
      |Configuration files in HOCON format can be passed through with the --conf flag.
      |Configuration files are merged by concatenating the files passed with --conf flags.
      |The last --conf [file] argument can override values specified in earlier --conf [file] arguments.
      |In the example below, where the same configuration path is used in file1.conf and file2.conf,
      |the configuration value in file2.conf takes precedence, overriding the value provided by file1.conf:
      |
      |kubectl cloudflow configure swiss-knife --conf file1.conf --conf file2.conf
      |
      |It is also possible to pass configuration values directly through the command-line as '[config-key]=value' pairs separated by
      |an equal sign. The [config-key] must be an absolute path to the value, exactly how it would be defined in a config file.
      |Some examples:
      |
      |kubectl cloudflow configure swiss-knife cloudflow.runtimes.spark.config.spark.driver.memoryOverhead=512
      |kubectl cloudflow configure swiss-knife cloudflow.streamlets.spark-process.config-parameters.configurable-message="SPARK-OUTPUT:"
      |
      |
      |The arguments passed with '[config-key]=[value]' pairs take precedence over the files passed through with the --conf flag.
      |""".stripMargin

}
