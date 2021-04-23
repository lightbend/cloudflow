

print(`Operation: ${operation}`);
print(`Application: ${application}`);


const runtime = "flink";
const serviceAccount = "flink-service-account";
const jsonConfigs = JSON.parse(configs);

var res = "";
for (const [key, value] of Object.entries(jsonConfigs)) {
  if (value.deployment.runtime == runtime) {
    /*
    print(`Flink streamlet! ${key}`);
    */
    res = res + generatePodTemplate(value) + generateFlinkCommand(value) + execCommand(value);
  }
}

print(res);

function execCommand(streamlet) {
  const clusterId = streamlet.deployment.name.replace(".", "-");

  const result = `(cd ${clusterId} && kubectl kustomize stage0 > pod-template.yaml && source deploy.sh)`;

  return result;
}

function generateFlinkCommand(streamlet) {
  const clusterId = streamlet.deployment.name.replace(".", "-");
  const dockerImage = streamlet.deployment.image;

  const result = `
mkdir -p ${clusterId}
cat << EOF > ${clusterId}/deploy.sh
flink run-application \\
    --target kubernetes-application \\
    -Dkubernetes.cluster-id=${clusterId} \\
    -Dkubernetes.service-account=${serviceAccount} \\
    -Dkubernetes.container.image=${dockerImage} \\
    -Dkubernetes.namespace=${application} \\
    -Dhigh-availability=org.apache.flink.kubernetes.highavailability.KubernetesHaServicesFactory \\
    -Dhigh-availability.storageDir=file:///mnt/flink/storage/ha \\
    -Dstate.checkpoints.dir=file:///mnt/flink/storage/externalized-checkpoints/${clusterId} \\
    -Dstate.backend.fs.checkpointdir=file:///mnt/flink/storage/checkpoints/${clusterId} \\
    -Dstate.savepoints.dir=file:///mnt/flink/storage/savepoints/${clusterId} \\
    -Dkubernetes.pod-template-file=output/pod-template.yaml \\
    local:///opt/flink/usrlib/cloudflow-runner.jar
EOF`;

  return result;
}

function generatePodTemplate(streamlet) {
  const clusterId = streamlet.deployment.name.replace(".", "-");
  const secretName = streamlet.deployment.secret_name;

  const volumes = JSON.parse(streamlet.configs["pods-config.conf"]).kubernetes.pods.pod.volumes;

  var pvcName = "not-available";
  var pvcClaimName = "not-available";
  for (const [key, value] of Object.entries(volumes)) {
    if (value.pvc) {
      pvcName = key;
      pvcClaimName = value.pvc.name;
      break;
    }
  }


  const result = `
mkdir -p ${clusterId}/base
cat << EOF > ${clusterId}/base/kustomization.yaml
resources:
  - pod-template.yaml
EOF
mkdir -p ${clusterId}/base
cat << EOF > ${clusterId}/base/pod-template.yaml
apiVersion: v1
kind: Pod
metadata:
  name: flink-pod-template
spec:
  containers:
    # Do not change the main container name
    - name: flink-main-container
      volumeMounts:
        - mountPath: /etc/cloudflow-runner-secret
          name: secret-vol
        - mountPath: /mnt/downward-api-volume/
          name: downward-api-volume
        - mountPath: /mnt/flink/storage
          name: default
  volumes:
  - name: secret-vol
    secret:
      defaultMode: 420
      secretName: streamlet-secret-name
  - downwardAPI:
      defaultMode: 420
      items:
      - fieldRef:
          apiVersion: v1
          fieldPath: metadata.uid
        path: metadata.uid
      - fieldRef:
          apiVersion: v1
          fieldPath: metadata.name
        path: metadata.name
      - fieldRef:
          apiVersion: v1
          fieldPath: metadata.namespace
        path: metadata.namespace
    name: downward-api-volume
EOF
mkdir -p ${clusterId}/stage0
cat << EOF > ${clusterId}/stage0/kustomization.yaml
bases:
  - ../base
patches:
  - stage0.yaml
EOF
mkdir -p ${clusterId}/stage0
cat << EOF > ${clusterId}/base/stage0.yaml
apiVersion: v1
kind: Pod
metadata:
  name: flink-pod-template
spec:
  volumes:
  - name: secret-vol
    secret:
      secretName: ${secretName}
  - name: ${pvcName}
    persistentVolumeClaim:
      claimName: ${pvcClaimName}
EOF
`;

  return result;
}
