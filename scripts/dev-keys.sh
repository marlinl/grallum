#!/bin/sh
# Create local development signing keys once; never overwrite an existing key pair.
set -eu
umask 077
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
key_dir=${1:-"$project_root/.local/secrets"}
mkdir -p "$key_dir"
if [ -e "$key_dir/jwt-private.pem" ] || [ -e "$key_dir/jwt-public.pem" ]; then
  test -s "$key_dir/jwt-private.pem" && test -s "$key_dir/jwt-public.pem"
  exit 0
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$key_dir/jwt-private.pem" 2>/dev/null
openssl pkey -in "$key_dir/jwt-private.pem" -pubout -out "$key_dir/jwt-public.pem" 2>/dev/null
chmod 600 "$key_dir/jwt-private.pem" "$key_dir/jwt-public.pem"
