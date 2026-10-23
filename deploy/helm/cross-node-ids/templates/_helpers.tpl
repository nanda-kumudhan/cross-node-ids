{{- define "ids.name" -}}
{{- printf "%s-ids" .Release.Name | trunc 48 | trimSuffix "-" -}}
{{- end -}}
{{- define "ids.labels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: cross-node-ids
{{- end -}}
