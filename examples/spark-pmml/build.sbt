name := "spark-pmml"

version := "0.1"

// https://mvnrepository.com/artifact/org.apache.spark/spark-core
libraryDependencies += "org.apache.spark" %% "spark-core" % "3.0.1"
// https://mvnrepository.com/artifact/org.apache.spark/spark-sql
libraryDependencies += "org.apache.spark" %% "spark-sql" % "3.0.1"
libraryDependencies += "org.apache.spark" %% "spark-mllib" % "3.0.1"
// https://mvnrepository.com/artifact/org.jpmml/jpmml-sparkml
libraryDependencies += "org.jpmml" % "jpmml-sparkml" % "1.6.1"


scalaVersion := "2.12.10"
