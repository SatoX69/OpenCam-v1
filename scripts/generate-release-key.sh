#!/usr/bin/env sh
set -eu
OUT="${1:-$HOME/.config/openlens/openlens-release.jks}"
ALIAS="${OPENLENS_KEY_ALIAS:-openlens}"
mkdir -p "$(dirname "$OUT")"
if [ -e "$OUT" ]; then
  echo "Refusing to overwrite existing key: $OUT" >&2
  exit 1
fi
printf 'Keystore password: '
stty -echo
read STORE_PASS
stty echo
printf '\nKey password (press Enter to reuse keystore password): '
stty -echo
read KEY_PASS
stty echo
printf '\n'
[ -n "$KEY_PASS" ] || KEY_PASS="$STORE_PASS"
keytool -genkeypair -v -keystore "$OUT" -storetype JKS -alias "$ALIAS" -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=OpenLens Release, OU=Open Source, O=OpenLens, L=Unknown, ST=Unknown, C=ZZ" -storepass "$STORE_PASS" -keypass "$KEY_PASS"
echo "Keystore created: $OUT"
echo "Alias: $ALIAS"
echo "Store it privately and back it up. Never commit it."
echo "Base64 secret (copy this into OPENLENS_KEYSTORE_BASE64 in GitHub Actions secrets):"
if base64 --help 2>&1 | grep -q -- '-w'; then base64 -w 0 "$OUT"; else base64 "$OUT" | tr -d '\r\n'; fi
echo
