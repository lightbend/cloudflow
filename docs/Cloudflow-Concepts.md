# Cloudflow - Core Concepts

This document gives you an overview of the main building blocks of a Cloudflow application.

## Application

A Cloudflow application is a collection of data processing components connected via data streams. A Cloudflow cluster can host multiple applications.

![Application](images/apps-1.png?resize=400,400&classes=center)

A Cloudflow application consists of the following basic building blocks:

1. Streamlets
2. Blueprint
3. Deployed Application

We will go through each of the above in the following sections.

![Application Building Blocks](images/apps-2.png?raw=true "Application Building Blocks")


## Streamlets

A streamlet is one of the building blocks of a Cloudflow application. Each streamlet represents a stage in the data transformation pipeline. Streamlets can have input endpoints called _inlets_ and output endpoints called _outlets_. Each inlet and outlet is typed and can handle data _of that specific type_ only.

Some of the commonly used streamlet shapes are the following:

### Ingress 

An Ingress is a streamlet with zero inlets and one or more outlets. An ingress could be a server handling requests e.g. using http.

![Ingress](images/streamlets-ingress.png?raw=true "Ingress")

### Processor

A Processor has one inlet and one outlet. Processors represent common data transformations like map and filter, or any combination of them.

![Processor](images/streamlets-processor.png?raw=true "Processor")

### FanOut

A FanOut (also known as a Splitter) has one inlet and more than one outlets. The FanOut splits the input into multiple data streams depending on some criteria. A typical example can be an implementation of validation where the FanOut splits the input into valid and invalid data streams.

![Splitter](images/streamlets-fanout.png?raw=true "Splitter")

### Egress

An Egress represents data leaving the Pipelines application. For instance this could be data being persisted to some database, notifications being sent to Slack, files being written to HDFS, etc.

![Egress](images/streamlets-egress.png?raw=true "Egress")

## Blueprint

A Blueprint connects streamlets together. This is what transforms a bunch of streamlets into your application pipeline. A blueprint is written in a file using a declarative language and is part of the project. 

![Egress](images/blueprint.png?raw=true "Egress")

## Deployed Application

A deployed application is the runtime realization of the blueprint. The pipeline gets formed according to the streamlets and connections specified in the blueprint and data flows across the streamlets.

![Deployed App](images/deploy-2.png?raw=true "Deployed App")
