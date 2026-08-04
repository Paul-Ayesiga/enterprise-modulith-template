{{- define "smsone.labels" -}}
app.kubernetes.io/part-of: smsone
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "smsone.image" -}}
{{ .root.Values.global.imageBase }}/{{ .name }}:{{ .root.Values.global.imageTag }}
{{- end }}
