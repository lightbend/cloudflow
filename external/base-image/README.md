### Cloudflow Runtime Image

The included Dockerfile builds a single image that can used in either a Flink streamlet or a Spark streamlet because it contains both Spark and Flink runtimes.

#### Building the image

```bash
$ docker build -t lightbend/cloudflow-base:1.3.0-M1-spark-2.4.4-flink-1.9.2-scala-2.12 .
```

Which can be pushed to a remote repository.

Note that the tag of the final image is composed of the following components:

1. Cloudflow version: `1.3.0`
2. Spark version: `2.4.4`
3. Flink version: `1.9.2`
4. Scala version: `2.12`
