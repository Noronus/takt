#!/bin/bash
# Manages access tokens on the Docker host (one per person). Takt picks up changes within 10 s.
# Usage: ./tokens.sh add NAME       create a token for NAME and print it (give it to that person)
#        ./tokens.sh remove NAME    revoke NAME's access
#        ./tokens.sh list           show who has access
set -euo pipefail
cd "$(dirname "$0")"
source deploy.env
: "${DEPLOY_SSH:?}"
DATA=${DATA:-/mnt/user/appdata/takt}
F=$DATA/tokens.txt
cmd=${1:-list}; name=${2:-}

if [ "$cmd" != list ] && ! [[ "$name" =~ ^[A-Za-z0-9_-]{1,32}$ ]]; then
  echo "NAME: letters, digits, - and _ only (max 32)"; exit 1
fi

case "$cmd" in
  add)
    token=$(openssl rand -base64 36 | tr '+/' '-_' | tr -d '=\n')
    $DEPLOY_SSH "set -e; touch $F; chmod 600 $F; chown 99:100 $F
      if awk '{print \$1}' $F | grep -qx '$name'; then echo 'NAME exists, remove it first' >&2; exit 1; fi
      echo '$name $token' >> $F"
    echo "Token for $name (shown once, send it over a private channel):"
    echo "$token"
    ;;
  remove)
    $DEPLOY_SSH "set -e; [ -f $F ] && awk '\$1 != \"$name\"' $F > $F.new && cat $F.new > $F && rm $F.new"
    echo "$name removed. Takt drops the token within 10 s."
    ;;
  list)
    $DEPLOY_SSH "[ -f $F ] && grep -v '^#' $F | awk 'NF {print \$1}' || echo '(no tokens)'"
    ;;
  *) echo "Usage: $0 add NAME | remove NAME | list"; exit 1 ;;
esac
