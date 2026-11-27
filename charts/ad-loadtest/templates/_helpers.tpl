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

{{/* Comma-separated DCs the load generators target: dc.hosts, else the single dc.host / Samba DC. */}}
{{- define "adlt.dcHosts" -}}
{{- if .Values.dc.hosts -}}
{{ join "," .Values.dc.hosts }}
{{- else -}}
{{ include "adlt.dcHost" . }}
{{- end -}}
{{- end }}

{{/* DC list for the pods: from the secret (e.g. Terraform's dc_ips) when dc.hostsSecretKey is set. */}}
{{- define "adlt.dcHostsEnv" -}}
- name: DC_HOSTS
{{- if .Values.dc.hostsSecretKey }}
  valueFrom: { secretKeyRef: { name: {{ include "adlt.secretName" . }}, key: {{ .Values.dc.hostsSecretKey }} } }
{{- else }}
  value: {{ include "adlt.dcHosts" . | quote }}
{{- end }}
{{- end }}

{{/*
Init container shared by the controller and the load generators: renders the target profile and
krb5.conf (password and DC list), and writes users.csv and computers.csv with the same scheme the
domain was seeded with (docker/samba-dc/seed-users.py, infra/aws/ad/dc1.ps1.tftpl).
*/}}
{{- define "adlt.prepare" -}}
- name: prepare
  image: "{{ .Values.controller.image.repository }}:{{ .Values.controller.image.tag }}"
  imagePullPolicy: {{ .Values.controller.image.pullPolicy }}
  command:
    - /bin/sh
    - -c
    - |
      set -e
      for f in /config-src/*; do
        awk 'function sub_all(s, t, v,   i) {
               while ((i = index(s, t)) > 0) s = substr(s, 1, i - 1) v substr(s, i + length(t))
               return s }
             index($0, "@KDCS@") { n = split(ENVIRON["DC_HOSTS"], h, ",")
                                   for (i = 1; i <= n; i++) print "    kdc = " h[i] ":88"; next }
             { s = sub_all($0, "@TEST_USER_PASSWORD@", ENVIRON["TEST_USER_PASSWORD"])
               print sub_all(s, "@DC_HOSTS@", ENVIRON["DC_HOSTS"]) }' "$f" > "/config/$(basename "$f")"
      done
      if [ -f /users-src/users.csv ]; then
        cp /users-src/users.csv /data/users.csv
      else
        awk -v n="$USER_COUNT" 'BEGIN {
          print "username,password,authorised,profile,expect"
          for (i = 1; i <= n; i++) {
            inchain = (i % 4 == 0 || i % 8 == 1 || i % 8 == 5)
            disabled = (i % 50 == 3); expired = (i % 50 == 7)
            if (disabled) p = "disabled"; else if (expired) p = "expired"; else if (i % 10 == 0) p = "heavy"
            else if (i % 8 == 5) p = "deep"; else if (i % 8 == 1) p = "nested"; else p = "standard"
            printf "ltuser%05d,%s,%s,%s,%s\n", i, ENVIRON["TEST_USER_PASSWORD"],
              (inchain && !disabled && !expired ? "true" : "false"), p, (disabled || expired ? "fail" : "ok")
          }
        }' > /data/users.csv
      fi
      if [ -f /users-src/computers.csv ]; then
        cp /users-src/computers.csv /data/computers.csv
      else
        awk -v n="$COMPUTER_COUNT" 'BEGIN {
          print "username,password,authorised,profile,expect"
          for (i = 1; i <= n; i++) printf "LTPC%05d$,%s,false,computer,ok\n", i, ENVIRON["TEST_USER_PASSWORD"]
        }' > /data/computers.csv
      fi
  env:
    - { name: USER_COUNT, value: {{ .Values.users.count | quote }} }
    - { name: COMPUTER_COUNT, value: {{ .Values.users.computerCount | quote }} }
    - name: TEST_USER_PASSWORD
      valueFrom: { secretKeyRef: { name: {{ include "adlt.secretName" . }}, key: testUserPassword } }
    {{- include "adlt.dcHostsEnv" . | nindent 4 }}
  volumeMounts:
    - { name: config-src, mountPath: /config-src }
    - { name: config, mountPath: /config }
    - { name: data, mountPath: /data }
    {{- if .Values.users.existingConfigMap }}
    - { name: users-src, mountPath: /users-src }
    {{- end }}
{{- end }}

{{- define "adlt.prepareVolumes" -}}
- name: config-src
  configMap: { name: {{ .Release.Name }}-config }
- { name: config, emptyDir: {} }
- { name: data, emptyDir: {} }
{{- if .Values.users.existingConfigMap }}
- name: users-src
  configMap: { name: {{ .Values.users.existingConfigMap }} }
{{- end }}
{{- end }}

{{/* JMeter servers used as load generators, by stable pod DNS name. */}}
{{- define "adlt.remoteHosts" -}}
{{- $hosts := list -}}
{{- range $i := until (int .Values.generators.replicas) -}}
{{- $hosts = append $hosts (printf "%s-generator-%d.%s-generator" $.Release.Name $i $.Release.Name) -}}
{{- end -}}
{{ join "," $hosts }}
{{- end }}
