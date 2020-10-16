
import javax.xml.transform.stream.StreamResult
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.sql.SparkSession
import org.apache.spark.mllib.clustering.KMeans
import org.apache.spark.mllib.linalg.Vectors
import org.jpmml.model.JAXBUtil
import org.jpmml.sparkml.PMMLBuilder

object ConnectedCar extends App {

  val spark = SparkSession.builder.appName("Simple Application").getOrCreate()

  // Load and parse the data
  val data = spark.read.textFile("data/mllib/kmeans_data.txt")
  val parsedData = data.map(s => Vectors.dense(s.split(' ').map(_.toDouble))).cache()

  // Cluster the data into two classes using KMeans
  val numClusters = 2
  val numIterations = 20
  val clusters = KMeans.train(parsedData, numClusters, numIterations)

  // Export to PMML to a String in PMML format
  println(s"PMML Model:\n ${clusters.toPMML}")

  // Export the model to a local file in PMML format
  clusters.toPMML("/tmp/kmeans.xml")

  // Export the model to a directory on a distributed file system in PMML format
  clusters.toPMML(sc, "/tmp/kmeans")

  // Export the model to the OutputStream in PMML format
  clusters.toPMML(System.out)

  val pmml = new PMMLBuilder(schema, pipelineModel)
    .build();

  // Viewing the result
  JAXBUtil.marshalPMML(pmml, new StreamResult(System.out));

  val pipeline = new Pipeline()
    .setStages(Array(lr))

  val pipelineModel = pipeline.fit(training);

  val pmml = new PMMLBuilder(training.schema(), pipelineModel)
    .build();

  // Viewing the result
  JAXBUtil.marshalPMML(pmml, new StreamResult(System.out));

}
