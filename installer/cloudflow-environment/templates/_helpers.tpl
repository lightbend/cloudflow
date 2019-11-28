{{/*
Expand the name of the chart.
*/}}
{{- define "operator.name" -}}
{{- default "cloudflow-operator" }}
{{- end -}}

{{/* Generate basic labels */}}
{{- define "operator.labels" }}
app.kubernetes.io/managed-by: helm
app.kubernetes.io/name:  {{ template "operator.name" . }}
app.kubernetes.io/part-of: cloudflow
app.kubernetes.io/version: {{ .Values.operator.image.tag }}
cloudflow.lightbend.com/release-version: {{ .Chart.Version }}
cloudflow.lightbend.com/build-number: {{ .Values.operator.image.tag }}
{{- if .Values.podLabels}}
{{ toYaml .Values.podLabels }}
{{- end }}
{{- end }}

{{/* Cloudflow Strimzi cluster name */}}
{{- define "operator.strimziClusterName" }}
{{- if eq .Values.kafka.mode "External" }}
{{- printf "external-kafka" }}
{{- else }}
{{- required "Strimzi Kafka cluster name cannot be empty" .Values.kafka.strimzi.name }}
{{- end }}
{{- end }}
