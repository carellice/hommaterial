#!/bin/sh
# Creates the local certificate authority and the HTTPS certificate when HTTPS_HOSTS is set
# (for example "192.168.1.10,casaos.local"). Phones trust it once, from /certificato.
set -e
CERTS="${DATA_DIR:-/data}/certs"

if [ -n "$HTTPS_HOSTS" ]; then
  mkdir -p "$CERTS"
  if [ ! -f "$CERTS/hommaterial-ca.key" ]; then
    echo "Creo l'autorità di certificazione locale di Hommaterial"
    openssl req -x509 -new -nodes -newkey rsa:2048 -sha256 -days 3650 \
      -keyout "$CERTS/hommaterial-ca.key" -out "$CERTS/hommaterial-ca.crt" -subj "/CN=Hommaterial CA" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1
  fi
  # A new certificate whenever the address list changes; phones keep trusting the same CA.
  if [ ! -f "$CERTS/server.crt" ] || [ "$(cat "$CERTS/hosts" 2>/dev/null)" != "$HTTPS_HOSTS" ]; then
    echo "Creo il certificato HTTPS per: $HTTPS_HOSTS"
    SAN=""
    OLDIFS=$IFS
    IFS=','
    for host in $HTTPS_HOSTS; do
      host=$(echo "$host" | tr -d ' ')
      [ -z "$host" ] && continue
      if echo "$host" | grep -Eq '^[0-9.]+$|:'; then SAN="$SAN,IP:$host"; else SAN="$SAN,DNS:$host"; fi
    done
    IFS=$OLDIFS
    SAN="${SAN#,}"
    first=$(echo "$HTTPS_HOSTS" | cut -d, -f1 | tr -d ' ')
    openssl req -new -nodes -newkey rsa:2048 -keyout "$CERTS/server.key" -out "$CERTS/server.csr" -subj "/CN=$first" >/dev/null 2>&1
    printf "subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n" "$SAN" > "$CERTS/ext.cnf"
    # 825 days: the longest validity Apple devices accept.
    openssl x509 -req -in "$CERTS/server.csr" -CA "$CERTS/hommaterial-ca.crt" -CAkey "$CERTS/hommaterial-ca.key" -CAcreateserial \
      -out "$CERTS/server.crt" -days 825 -sha256 -extfile "$CERTS/ext.cnf" >/dev/null 2>&1
    rm -f "$CERTS/server.csr" "$CERTS/ext.cnf"
    echo "$HTTPS_HOSTS" > "$CERTS/hosts"
  fi
else
  # HTTPS turned off: the server only listens in HTTP.
  rm -f "$CERTS/server.crt" "$CERTS/server.key" "$CERTS/hosts" 2>/dev/null || true
fi

exec "$@"
