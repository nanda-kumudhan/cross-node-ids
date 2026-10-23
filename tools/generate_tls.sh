#!/usr/bin/env bash
# Generate certificates for VM tests.
set -euo pipefail
umask 077
broker_ip=${1:?Usage: generate_tls.sh BROKER_IP [OUTPUT_DIRECTORY]}
output=${2:-deploy/tls}
python3 -c 'import ipaddress,sys; ipaddress.ip_address(sys.argv[1])' "$broker_ip"
if [ -e "$output" ]; then
  printf 'Refusing to overwrite TLS directory: %s\n' "$output" >&2
  exit 1
fi
mkdir -p "$output/ca" "$output/core" "$output/engine" "$output/node-a" "$output/node-b"
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 30 \
  -subj /CN=cross-node-test-ca -addext basicConstraints=critical,CA:TRUE \
  -addext keyUsage=critical,keyCertSign,cRLSign \
  -keyout "$output/ca/ca.key" -out "$output/ca/ca.crt" 2>/dev/null
for name in core engine node-a node-b; do
  dir="$output/$name"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/client.key" 2>/dev/null
  openssl req -new -key "$dir/client.key" -subj "/CN=$name" -out "$dir/request.csr"
  if [ "$name" = core ]; then
    printf 'subjectAltName=IP:%s,DNS:kafka\nextendedKeyUsage=serverAuth\n' "$broker_ip" > "$dir/extensions.cnf"
  else
    printf 'extendedKeyUsage=clientAuth\n' > "$dir/extensions.cnf"
  fi
  openssl x509 -req -in "$dir/request.csr" -CA "$output/ca/ca.crt" \
    -CAkey "$output/ca/ca.key" -CAcreateserial -days 14 -sha256 \
    -extfile "$dir/extensions.cnf" -out "$dir/client.crt" 2>/dev/null
  cp "$output/ca/ca.crt" "$dir/ca.crt"
  cat "$dir/client.key" "$dir/client.crt" > "$dir/client.pem"
  if [ "$name" = core ]; then cp "$dir/client.pem" "$dir/server.pem"; fi
done
printf 'Generated short-lived test certificates in %s. See docs/deployment.md for container ownership.\n' "$output"
