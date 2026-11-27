{{/* Chart name. */}}
{{- define "helpdesk.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Release-scoped base name; component names append "-<component>". */}}
{{- define "helpdesk.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 40 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 40 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Name of one component's objects. Call with (dict "root" $ "component" "assistant"). */}}
{{- define "helpdesk.componentName" -}}
{{- printf "%s-%s" (include "helpdesk.fullname" .root) .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "helpdesk.selectorLabels" -}}
app.kubernetes.io/name: {{ include "helpdesk.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "helpdesk.labels" -}}
{{ include "helpdesk.selectorLabels" . }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: helpdesk
{{- end -}}

{{/*
Image reference. Call with (dict "root" $ "image" .Values.x.image).
image.repository (full) wins over global.imageRegistry/image.name. A digest wins over the default
tags; only the component's own image.tag is kept next to it, for readability (repo:tag@digest).
*/}}
{{- define "helpdesk.image" -}}
{{- $repo := .image.repository | default (printf "%s/%s" .root.Values.global.imageRegistry .image.name) -}}
{{- if .image.digest -}}
{{- if .image.tag -}}
{{- printf "%s:%s@%s" $repo .image.tag .image.digest -}}
{{- else -}}
{{- printf "%s@%s" $repo .image.digest -}}
{{- end -}}
{{- else -}}
{{- printf "%s:%s" $repo (.image.tag | default .root.Values.global.imageTag | default .root.Chart.AppVersion) -}}
{{- end -}}
{{- end -}}

{{/* Env var from an existing Secret. Call with (dict "name" "X" "secret" "s" "key" "k" "optional" false). */}}
{{- define "helpdesk.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: {{ .key }}
      {{- if .optional }}
      optional: true
      {{- end }}
{{- end -}}

{{/* Tracing env (K8) shared by the Java services. */}}
{{- define "helpdesk.tracingEnv" -}}
- name: TRACING_SAMPLING_PROBABILITY
  value: {{ .Values.global.tracingSamplingProbability | quote }}
{{- with .Values.global.otlpEndpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- end -}}

{{/*
Startup, readiness and liveness probes on the health groups (K7, T5.2).
Call with (dict "port" "http" "values" .Values.x); a missing startupProbe in values means none.
*/}}
{{- define "helpdesk.actuatorProbes" -}}
{{- with .values.startupProbe }}
startupProbe:
  httpGet: {path: /actuator/health/liveness, port: {{ $.port }}}
  periodSeconds: {{ .periodSeconds }}
  failureThreshold: {{ .failureThreshold }}
  timeoutSeconds: 3
{{- end }}
readinessProbe:
  httpGet: {path: /actuator/health/readiness, port: {{ .port }}}
  periodSeconds: {{ .values.readinessProbe.periodSeconds }}
  failureThreshold: {{ .values.readinessProbe.failureThreshold }}
  timeoutSeconds: 3
livenessProbe:
  httpGet: {path: /actuator/health/liveness, port: {{ .port }}}
  periodSeconds: {{ .values.livenessProbe.periodSeconds }}
  failureThreshold: {{ .values.livenessProbe.failureThreshold }}
  timeoutSeconds: 3
{{- end -}}

{{/* preStop pause, so endpoints drop the pod before it stops accepting connections. */}}
{{- define "helpdesk.lifecycle" -}}
{{- if .preStopSleepSeconds }}
lifecycle:
  preStop:
    sleep: {seconds: {{ .preStopSleepSeconds }}}
{{- end }}
{{- end -}}

{{/*
Pod-level settings shared by every component.
Call with (dict "root" $ "component" "assistant" "values" .Values.assistant).
*/}}
{{- define "helpdesk.podSpecCommon" -}}
{{- with .root.Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
terminationGracePeriodSeconds: {{ .values.terminationGracePeriodSeconds }}
{{- with .values.podSecurityContext }}
securityContext:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .values.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .values.affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- if .root.Values.global.spreadAcrossNodes }}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels:
        {{- include "helpdesk.selectorLabels" . | nindent 8 }}
{{- end }}
{{- end -}}

{{/*
Writable /tmp for every container. It is a plain emptyDir, which Kubernetes does not mount noexec:
onnxruntime (assistant) unpacks and maps a native library there, which fails on a noexec /tmp.
It also keeps /tmp working once C11b sets readOnlyRootFilesystem.
*/}}
{{- define "helpdesk.tmpVolume" -}}
- name: tmp
  emptyDir: {}
{{- end -}}

{{- define "helpdesk.tmpVolumeMount" -}}
- name: tmp
  mountPath: /tmp
{{- end -}}

{{/* PodDisruptionBudget. Call with (dict "root" $ "component" "assistant" "values" .Values.assistant). */}}
{{- define "helpdesk.pdb" -}}
{{- if .values.pdb.enabled }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "helpdesk.componentName" . }}
  labels:
    {{- include "helpdesk.labels" . | nindent 4 }}
spec:
  maxUnavailable: {{ .values.pdb.maxUnavailable }}
  selector:
    matchLabels:
      {{- include "helpdesk.selectorLabels" . | nindent 6 }}
{{- end }}
{{- end -}}

{{/* Service URLs inside the cluster. MCP clients use the base URL; the assistant appends /mcp (K6). */}}
{{- define "helpdesk.assistantUrl" -}}
http://{{ include "helpdesk.componentName" (dict "root" . "component" "assistant") }}:8080
{{- end -}}

{{- define "helpdesk.ticketServerUrl" -}}
http://{{ include "helpdesk.componentName" (dict "root" . "component" "ticket-mcp-server") }}:8081
{{- end -}}

{{- define "helpdesk.requestLogServerUrl" -}}
http://{{ include "helpdesk.componentName" (dict "root" . "component" "request-log-mcp-server") }}:8082
{{- end -}}

{{- define "helpdesk.uiUrl" -}}
http://{{ include "helpdesk.componentName" (dict "root" . "component" "ui") }}:8080
{{- end -}}
