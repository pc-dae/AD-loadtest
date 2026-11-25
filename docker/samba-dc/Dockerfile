FROM debian:trixie-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      samba samba-ad-dc samba-ad-provision winbind krb5-user smbclient ldb-tools \
      python3-samba dnsutils procps \
 && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh seed-users.py /usr/local/bin/
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 53 53/udp 88 88/udp 389 445 464 636 3268 3269
VOLUME ["/var/lib/samba", "/etc/samba"]
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
