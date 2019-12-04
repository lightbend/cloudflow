# Fraud Detection 

> Note: This is a demo application and is not to be deployed in projection without express permissions from Lightbend

## Getting Started

For any information about Lightbend Pipelines please reference the offical documentation
https://developer.lightbend.com/docs/pipelines/1.2.0/

### Downloading The Data and H2O MOJO

The data is available in a `.zip` file stored here

https://drive.google.com/drive/folders/1f0tIbfCdhTOuLIdNSur8CPsU_GijEzHU?usp=sharing

- Download the "creditcard.csv.zip" file
- Unzip it
- Place a copy in 
  - `./pipelines-fraud-detection/pipelines/src/main/resources/data`
  
- Download the "fraud-detection.mojo" file
- Place a copy in 
  - `./pipelines-fraud-detection/pipelines/src/main/resources/models`  
  
### Running Locally

To run all streamlets and using a local embedded kafka cluster run the command below
https://developer.lightbend.com/docs/pipelines/1.2.0/#_using_sandbox

```shell script
sbt runLocal
```

### Installing Pipelines
[Installing on Openshift](https://developer.lightbend.com/docs/pipelines/1.2.0/#_installing)

[Installing on AKS](./INSTALL-PIPELINES-AKS.md)

### Deploying the Application

#### Build and Publish

Follow the instructions detailed here in the documentation
https://developer.lightbend.com/docs/pipelines/1.2.0/#_publish_the_application

#### Deploying 

https://developer.lightbend.com/docs/pipelines/1.2.0/#_publish_the_application

For AKS use the command below

```
kubectl pipelines deploy {Full_Docker_Image_Name}
```
