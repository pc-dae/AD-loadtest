#!/bin/bash
# Provisions a single-DC Samba AD domain on first start, seeds load-test users,
# then runs samba in the foreground.
set -euo pipefail

REALM="${REALM:-LAB.LOCAL}"
DOMAIN="${DOMAIN:-LAB}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
DNS_FORWARDER="${DNS_FORWARDER:-127.0.0.11}"
HOST_NAME="${HOST_NAME:-$(hostname -s)}"

if [ ! -f /var/lib/samba/private/sam.ldb ]; then
  echo "Provisioning ${REALM} (${DOMAIN})..."
  rm -f /etc/samba/smb.conf
  samba-tool domain provision \
    --use-rfc2307 \
    --realm="${REALM}" \
    --domain="${DOMAIN}" \
    --server-role=dc \
    --host-name="${HOST_NAME}" \
    --dns-backend=SAMBA_INTERNAL \
    --adminpass="${ADMIN_PASSWORD}" \
    --option="dns forwarder = ${DNS_FORWARDER}" \
    --option="ldap server require strong auth = no" \
    --option="ntlm auth = ntlmv2-only" \
    --option="log level = 1"

  # Load tests hammer the same accounts and occasionally send bad passwords:
  # no lockout, expiry or complexity rules in the lab.
  samba-tool domain passwordsettings set \
    --complexity=off --history-length=0 --min-pwd-age=0 --max-pwd-age=0 \
    --account-lockout-threshold=0

  python3 /usr/local/bin/seed-users.py
fi

cp /var/lib/samba/private/krb5.conf /etc/krb5.conf
exec samba --foreground --no-process-group --debug-stdout
