{{- define "adlt.name" -}}{{ .Release.Name }}{{- end }}

{{- define "adlt.labels" -}}
app.kubernetes.io/part-of: ad-loadtest
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "adlt.selector" -}}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "adlt.secretName" -}}
{{- .Values.secrets.existingSecret | default (printf "%s-secrets" .Release.Name) -}}
{{- end }}

{{/* Host the load generators target: an explicit DC, or the in-cluster Samba service. */}}
{{- define "adlt.dcHost" -}}
{{- if .Values.dc.host -}}
{{ .Values.dc.host }}
{{- else if .Values.samba.enabled -}}
{{ .Release.Name }}-dc.{{ .Release.Namespace }}.svc.cluster.local
{{- else -}}
{{ fail "Set dc.host when samba.enabled is false" }}
{{- end -}}
{{- end }}

{{- define "adlt.storageClass" -}}
{{- if . }}
storageClassName: {{ . }}
{{- end }}
{{- end }}
