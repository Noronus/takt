#!/bin/bash
# Installed by deploy.sh to /boot/config/plugins/takt/autoupdate.sh, run every 5 minutes by cron
# (/boot/config/plugins/dynamix/takt-autoupdate.cron). Checks only the Takt containers: asks
# ghcr.io for the digest of each container's image tag and, if it changed, updates the container
# through Unraid's own updater (same as the update button, recreated from its template).
# Only two small HTTP requests per image and run; nothing is pulled unless there is an update.
CONTAINERS="Takt Takt-MOTIS"
exec 9>/var/run/takt-autoupdate.lock
flock -n 9 || exit 0
log() { logger -t takt-autoupdate "$*"; }

remote_digest() { # ghcr.io/owner/name:tag -> sha256:...
  local ref=${1#ghcr.io/} repo tag token
  repo=${ref%:*}; tag=${ref##*:}
  token=$(curl -fsS -m 20 "https://ghcr.io/token?scope=repository:$repo:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p') || return 1
  curl -fsSI -m 20 -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json" \
    "https://ghcr.io/v2/$repo/manifests/$tag" | tr -d '\r' | sed -n 's/^docker-content-digest: //Ip'
}

for c in $CONTAINERS; do
  image=$(docker inspect -f '{{.Config.Image}}' "$c" 2>/dev/null) || continue
  case "$image" in ghcr.io/*:*) ;; *) continue;; esac
  old_id=$(docker inspect -f '{{.Image}}' "$c")
  local_digest=$(docker image inspect -f '{{range .RepoDigests}}{{.}} {{end}}' "$old_id" | tr ' ' '\n' | sed -n 's/.*@//p' | head -1)
  remote=$(remote_digest "$image")
  [ -n "$remote" ] || { log "$c: no answer from ghcr.io"; continue; }
  [ "$remote" = "$local_digest" ] && continue

  log "$c: update $image ($local_digest -> $remote)"
  /usr/local/emhttp/plugins/dynamix.docker.manager/scripts/update_container "$c" >/dev/null 2>&1
  new_id=$(docker inspect -f '{{.Image}}' "$c" 2>/dev/null)
  if [ -n "$new_id" ] && [ "$new_id" != "$old_id" ] && [ "$(docker inspect -f '{{.State.Running}}' "$c")" = true ]; then
    docker rmi "$old_id" >/dev/null 2>&1
    log "$c: updated"
    /usr/local/emhttp/webGui/scripts/notify -e "Takt" -s "$c aktualisiert" -d "$image" -i normal
  else
    log "$c: update FAILED"
    /usr/local/emhttp/webGui/scripts/notify -e "Takt" -s "Update von $c fehlgeschlagen" -d "$image, siehe Docker-Tab" -i warning
  fi
done
