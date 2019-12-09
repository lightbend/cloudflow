#!/usr/bin/env bash
if [ -z "$1" ]
  then
    echo "Please provide the docker registry to push the models image to, for instance eu.gcr.io/<projectID> (replace <projectID> with your own project)."
    exit 1
fi
DOCKER_REPO=$1

docker build -t copy-models .
docker tag copy-models:latest $DOCKER_REPO/copy-models:latest
docker push $DOCKER_REPO/copy-models:latest

kubectl -n tensorflow-akka delete job copy-models --cascade --ignore-not-found

cat << EOF > temp-job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: copy-models
  namespace: tensorflow-akka
spec:
  template:
    spec:
      volumes:
        - name: models
          persistentVolumeClaim:
            claimName: claim1
      containers:
      - name: copy-models
        image: $DOCKER_REPO/copy-models:latest
        volumeMounts:
          - mountPath: "/models"
            name: models
        command: ["cp",  "-r", "/staging/models", "/"]
      restartPolicy: Never
  backoffLimit: 4
EOF
kubectl apply -f temp-job.yaml
rm temp-job.yaml