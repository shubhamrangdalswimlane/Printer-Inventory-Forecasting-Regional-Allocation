{{/*
_helpers.tpl - shared template snippets: names, labels, image references, the pod security settings and the
backend pod spec used by BOTH the api and the worker Deployments (they run the same image; only env differs).
*/}}

{{/* Chart name, overridable. */}}
{{- define "pifa.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Resource-name prefix: "<release>-pifa", or just "<release>" if the release name already contains "pifa". */}}
{{- define "pifa.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Labels on every object (Kubernetes recommended labels). */}}
{{- define "pifa.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "pifa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: pifa
{{- end }}

{{/* Selector labels for one component. Call with (dict "root" $ "component" "api"). Never change these after
the first install: Deployment selectors are immutable. */}}
{{- define "pifa.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pifa.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/* Full labels for one component. */}}
{{- define "pifa.componentLabels" -}}
{{ include "pifa.labels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/* Backend image reference; a digest wins over the tag. */}}
{{- define "pifa.backendImage" -}}
{{- if .Values.image.digest -}}
{{ .Values.image.repository }}@{{ .Values.image.digest }}
{{- else -}}
{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}
{{- end }}
{{- end }}

{{/* Host name the application uses for ClamAV: explicit value, or the in-chart Service's namespaced name
(which is one of the names in the server certificate). */}}
{{- define "pifa.clamavHost" -}}
{{- if .Values.config.clamav.host -}}
{{ .Values.config.clamav.host }}
{{- else -}}
{{ include "pifa.fullname" . }}-clamav.{{ .Release.Namespace }}.svc
{{- end }}
{{- end }}

{{/* Fails rendering on settings that cannot work, instead of deploying something broken. */}}
{{- define "pifa.validate" -}}
{{- if ne .Values.config.storage.type "s3" }}
{{- fail "config.storage.type must be \"s3\": api and worker run in separate pods and need shared storage (the \"local\" store is per pod)" }}
{{- end }}
{{- if not .Values.config.sftp.strictHostKey }}
{{- fail "config.sftp.strictHostKey must stay true outside local development (architecture.md 12: strict host-key verification)" }}
{{- end }}
{{- end }}

{{/* Default soft anti-affinity: prefer one replica per node, so a node failure takes out at most one. */}}
{{- define "pifa.affinity" -}}
{{- if .affinity }}
{{- toYaml .affinity }}
{{- else }}
podAntiAffinity:
  preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 100
      podAffinityTerm:
        topologyKey: kubernetes.io/hostname
        labelSelector:
          matchLabels:
            {{- include "pifa.selectorLabels" (dict "root" .root "component" .component) | nindent 12 }}
{{- end }}
{{- end }}

{{/* Spread replicas over availability zones where possible (architecture.md 13: multi-AZ cluster). Soft, so a
single-zone dev cluster still schedules. */}}
{{- define "pifa.topologySpread" -}}
- maxSkew: 1
  topologyKey: topology.kubernetes.io/zone
  whenUnsatisfiable: ScheduleAnyway
  labelSelector:
    matchLabels:
      {{- include "pifa.selectorLabels" (dict "root" .root "component" .component) | nindent 6 }}
{{- end }}

{{/*
Backend pod template, shared by api and worker. Call with
  (dict "root" $ "component" "api|worker" "cfg" .Values.api|.Values.worker "worker" true|false)
*/}}
{{- define "pifa.backendPodTemplate" -}}
{{- $root := .root }}
{{- $v := $root.Values }}
metadata:
  labels:
    {{- include "pifa.componentLabels" (dict "root" $root "component" .component) | nindent 4 }}
  annotations:
    # Restart pods when the shared settings change (env vars are read only at start).
    checksum/config: {{ include (print $root.Template.BasePath "/backend-configmap.yaml") $root | sha256sum }}
    {{- with .cfg.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  serviceAccountName: {{ include "pifa.fullname" $root }}-backend
  # No Kubernetes API access is needed. (IRSA still works: the EKS webhook injects its own projected AWS token.)
  automountServiceAccountToken: false
  {{- with $v.imagePullSecrets }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  securityContext:
    {{- toYaml $v.podSecurityContext | nindent 4 }}
  # Spring Boot shuts down gracefully (in-flight requests finish); give it time before SIGKILL.
  terminationGracePeriodSeconds: 45
  {{- if $v.otel.enabled }}
  initContainers:
    # Copies the OpenTelemetry Java agent into a shared volume (the app image does not contain it).
    - name: otel-agent
      image: {{ $v.otel.agentImage }}
      imagePullPolicy: IfNotPresent
      command: ["cp", "/javaagent.jar", "/otel/javaagent.jar"]
      securityContext:
        {{- toYaml $v.containerSecurityContext | nindent 8 }}
      resources:
        {{- toYaml $v.otel.initResources | nindent 8 }}
      volumeMounts:
        - { name: otel, mountPath: /otel }
  {{- end }}
  containers:
    - name: {{ .component }}
      image: {{ include "pifa.backendImage" $root }}
      imagePullPolicy: {{ $v.image.pullPolicy }}
      ports:
        # API port (routed by the Service/Ingress for api pods only; worker pods get no traffic on it).
        - { name: http, containerPort: 8080, protocol: TCP }
        # Internal management port: probes and Prometheus only. NEVER routed through the Ingress.
        - { name: management, containerPort: 9091, protocol: TCP }
      envFrom:
        - configMapRef:
            name: {{ include "pifa.fullname" $root }}-backend
      env:
        # api pods: no background jobs, no SFTP. worker pods: both (single-runner via advisory locks).
        - { name: PIFA_JOBS_ENABLED, value: {{ ternary "true" "false" .worker | quote }} }
        - { name: PIFA_SFTP_ENABLED, value: {{ ternary "true" "false" .worker | quote }} }
        {{- if $v.otel.enabled }}
        - name: JAVA_TOOL_OPTIONS
          value: {{ printf "%s -javaagent:/otel/javaagent.jar" $v.config.javaToolOptions | quote }}
        - { name: OTEL_SERVICE_NAME, value: {{ printf "pifa-%s" .component | quote }} }
        - { name: OTEL_EXPORTER_OTLP_ENDPOINT, value: {{ $v.otel.endpoint | quote }} }
        - { name: OTEL_EXPORTER_OTLP_PROTOCOL, value: {{ $v.otel.protocol | quote }} }
        # Metrics stay on Micrometer/Prometheus and logs on stdout; the agent only exports traces.
        - { name: OTEL_METRICS_EXPORTER, value: "none" }
        - { name: OTEL_LOGS_EXPORTER, value: "none" }
        - name: OTEL_RESOURCE_ATTRIBUTES
          value: {{ printf "service.namespace=pifa,deployment.environment=%s,k8s.namespace.name=%s" (default "default" $v.config.springProfilesActive) $root.Release.Namespace | quote }}
        {{- else }}
        - { name: JAVA_TOOL_OPTIONS, value: {{ $v.config.javaToolOptions | quote }} }
        {{- end }}
        - name: PIFA_DB_USER
          valueFrom:
            secretKeyRef: { name: {{ $v.secrets.db.name }}, key: {{ $v.secrets.db.usernameKey }} }
        - name: PIFA_DB_PASSWORD
          valueFrom:
            secretKeyRef: { name: {{ $v.secrets.db.name }}, key: {{ $v.secrets.db.passwordKey }} }
        {{- if $v.config.keycloak.adminUrl }}
        - name: PIFA_KEYCLOAK_READER_SECRET
          valueFrom:
            secretKeyRef: { name: {{ $v.secrets.keycloakReader.name }}, key: {{ $v.secrets.keycloakReader.key }} }
        {{- end }}
        {{- if $v.config.clamav.enabled }}
        - { name: PIFA_CLAMAV_CA_CERT, value: /etc/pifa/clamav/ca.crt }
        {{- end }}
        {{- if .worker }}
        - { name: PIFA_SFTP_PRIVATE_KEY, value: /etc/pifa/sftp/id_key }
        - { name: PIFA_SFTP_KNOWN_HOSTS, value: /etc/pifa/sftp/known_hosts }
        # Clear the dev-only default password from application.yml: production authenticates by key only.
        - { name: PIFA_SFTP_PASSWORD, value: "" }
        {{- end }}
        {{- with $v.config.extraEnv }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
        {{- with .cfg.extraEnv }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      startupProbe:
        httpGet: { path: /actuator/health/liveness, port: management }
        initialDelaySeconds: {{ $v.probes.startup.initialDelaySeconds }}
        periodSeconds: {{ $v.probes.startup.periodSeconds }}
        failureThreshold: {{ $v.probes.startup.failureThreshold }}
      livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: management }
        periodSeconds: {{ $v.probes.liveness.periodSeconds }}
        timeoutSeconds: {{ $v.probes.liveness.timeoutSeconds }}
        failureThreshold: {{ $v.probes.liveness.failureThreshold }}
      readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: management }
        periodSeconds: {{ $v.probes.readiness.periodSeconds }}
        timeoutSeconds: {{ $v.probes.readiness.timeoutSeconds }}
        failureThreshold: {{ $v.probes.readiness.failureThreshold }}
      lifecycle:
        # Give the Service/Ingress a few seconds to stop sending traffic before Spring begins shutting down.
        # (exec rather than the newer "sleep" action, which needs Kubernetes >= 1.30.)
        preStop:
          exec: { command: ["sleep", "5"] }
      securityContext:
        {{- toYaml $v.containerSecurityContext | nindent 8 }}
      resources:
        {{- toYaml .cfg.resources | nindent 8 }}
      volumeMounts:
        # The only writable path: JVM perf data, Tomcat work dirs, multipart upload buffering, POI temp files.
        - { name: tmp, mountPath: /tmp }
        {{- if $v.otel.enabled }}
        - { name: otel, mountPath: /otel, readOnly: true }
        {{- end }}
        {{- if $v.config.clamav.enabled }}
        - { name: clamav-ca, mountPath: /etc/pifa/clamav, readOnly: true }
        {{- end }}
        {{- if .worker }}
        - { name: sftp, mountPath: /etc/pifa/sftp, readOnly: true }
        {{- end }}
  volumes:
    - name: tmp
      emptyDir:
        # Uploads are <= 11 MB but several can be in flight; POI may spill to disk for big workbooks.
        sizeLimit: 1Gi
    {{- if $v.otel.enabled }}
    - name: otel
      emptyDir: { sizeLimit: 64Mi }
    {{- end }}
    {{- if $v.config.clamav.enabled }}
    - name: clamav-ca
      secret:
        secretName: {{ $v.secrets.clamavCa.name }}
        defaultMode: 0440
        items:
          - { key: {{ $v.secrets.clamavCa.key }}, path: ca.crt }
    {{- end }}
    {{- if .worker }}
    - name: sftp
      secret:
        secretName: {{ $v.secrets.sftp.name }}
        # Owner+group read only (group = fsGroup 1000); OpenSSH-style libraries dislike world-readable keys.
        defaultMode: 0440
        items:
          - { key: {{ $v.secrets.sftp.privateKeyKey }}, path: id_key }
          - { key: {{ $v.secrets.sftp.knownHostsKey }}, path: known_hosts }
    {{- end }}
  {{- with .cfg.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .cfg.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  affinity:
    {{- include "pifa.affinity" (dict "root" $root "component" .component "affinity" .cfg.affinity) | nindent 4 }}
  topologySpreadConstraints:
    {{- include "pifa.topologySpread" (dict "root" $root "component" .component) | nindent 4 }}
{{- end }}
