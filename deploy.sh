#!/bin/bash
# Pulls the Takt image from ghcr.io on the Docker host (Unraid) via SSH, installs the two Unraid
# templates and (re)creates the containers Takt and Takt-MOTIS. Data is kept.
# Usage: ./deploy.sh            update Takt (MOTIS only if missing)
#        ./deploy.sh --local    build the image from this checkout instead and upload it (unreleased changes)
#        ./deploy.sh --motis    also recreate Takt-MOTIS (e.g. after a MOTIS version change)
#        ./deploy.sh --seed DIR copy prepared data from DIR (cache/, static/, motis/input/) first
#        ./deploy.sh --private  only copy private/ (plug-ins, not in git) and restart Takt
# private/ (gitignored) is copied to DATA/private/ on every deploy; image updates leave it alone.
# TAKT_IMAGE in deploy.env picks another image or tag (default ghcr.io/noronus/takt:latest).
set -euo pipefail
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "deploy.env missing, see deploy.env.example"; exit 1; }
source deploy.env
: "${DEPLOY_SSH:?}" "${DEPLOY_HOST:?}"
API_PORT=${API_PORT:-8097}; MOTIS_PORT=${MOTIS_PORT:-8098}; DATA=${DATA:-/mnt/user/appdata/takt}; MOTIS_VERSION=${MOTIS_VERSION:-2.11}; REQUIRE_TOKEN=${REQUIRE_TOKEN:-false}
IMAGE=${TAKT_IMAGE:-ghcr.io/noronus/takt:latest}
RECREATE_MOTIS=0; SEED=""; LOCAL=0; ONLY_PRIVATE=0
while [ $# -gt 0 ]; do case "$1" in --motis) RECREATE_MOTIS=1;; --local) LOCAL=1;; --seed) SEED="$2"; shift;; --private) ONLY_PRIVATE=1;; esac; shift; done

# private plug-ins: the folder on the server mirrors private/ (removed files go away)
sync_private() {
  if [ -d private ] && ls private/*.ts >/dev/null 2>&1; then
    echo "== private plug-ins: $(cd private && ls *.ts | tr '\n' ' ')"
    tar -C private -cf - $(cd private && ls *.ts) | $DEPLOY_SSH "rm -rf $DATA/private && mkdir -p $DATA/private && tar -C $DATA/private -xf - && chown -R 99:100 $DATA/private && chmod 700 $DATA/private"
  else
    $DEPLOY_SSH "rm -rf $DATA/private"
  fi
}
if [ "$ONLY_PRIVATE" = 1 ]; then
  sync_private
  $DEPLOY_SSH "docker restart Takt >/dev/null"
  echo "Done: Takt restarted"; exit 0
fi
[ "$LOCAL" = 1 ] && IMAGE=takt:local

render() {
  sed -e "s#__HOST__#$DEPLOY_HOST#g" -e "s#__API_PORT__#$API_PORT#g" -e "s#__MOTIS_PORT__#$MOTIS_PORT#g" \
      -e "s#__MOTIS_VERSION__#$MOTIS_VERSION#g" -e "s#__REQUIRE_TOKEN__#$REQUIRE_TOKEN#g" -e "s#/mnt/user/appdata/takt#$DATA#g" \
      -e "s#ghcr.io/noronus/takt:latest#$IMAGE#g" "$1"
}

if [ "$LOCAL" = 1 ]; then
  echo "== build image (local)"
  docker build -q -t "$IMAGE" . >/dev/null
  echo "== upload image"
  docker save "$IMAGE" | gzip -1 | $DEPLOY_SSH 'gunzip | docker load' >/dev/null
else
  echo "== pull $IMAGE"
  $DEPLOY_SSH "docker pull -q $IMAGE >/dev/null"
fi

echo "== templates + data folders"
T=/boot/config/plugins/dockerMan/templates-user
render unraid/my-Takt.xml | $DEPLOY_SSH "cat > $T/my-Takt.xml"
render unraid/my-Takt-MOTIS.xml | $DEPLOY_SSH "cat > $T/my-Takt-MOTIS.xml"
# auto update of Takt and Takt-MOTIS every 5 minutes (only these two, see the script)
$DEPLOY_SSH "mkdir -p /boot/config/plugins/takt && cat > /boot/config/plugins/takt/autoupdate.sh" < unraid/takt-autoupdate.sh
$DEPLOY_SSH "echo '*/5 * * * * /bin/bash /boot/config/plugins/takt/autoupdate.sh >/dev/null 2>&1' > /boot/config/plugins/dynamix/takt-autoupdate.cron && /usr/local/sbin/update_cron"
$DEPLOY_SSH "mkdir -p $DATA/motis && chown -R 99:100 $DATA && { [ ! -f $DATA/tokens.txt ] || chmod 600 $DATA/tokens.txt; }"

if [ -n "$SEED" ]; then
  echo "== seed data from $SEED"
  tar -C "$SEED" -cf - --exclude='cache/osm' --exclude='motis/build-*' --exclude='motis/current' --exclude='motis/run.sh' \
      cache static motis/input state.json | $DEPLOY_SSH "tar -C $DATA -xf - && chown -R 99:100 $DATA"
fi

sync_private

echo "== container Takt"
$DEPLOY_SSH "docker rm -f Takt >/dev/null 2>&1; docker run -d --name Takt --user=99:100 --restart=unless-stopped \
  --cap-drop=ALL --security-opt=no-new-privileges --memory=4g \
  -p $API_PORT:8080 -v $DATA:/data -e TZ=Europe/Berlin -e REQUIRE_TOKEN=$REQUIRE_TOKEN \
  -e MOTIS_URL=http://$DEPLOY_HOST:$MOTIS_PORT -e SELF_URL=http://$DEPLOY_HOST:$API_PORT \
  -l net.unraid.docker.managed=dockerman -l net.unraid.docker.icon=http://$DEPLOY_HOST:$API_PORT/icon.png \
  -l 'net.unraid.docker.webui=http://[IP]:[PORT:8080]/api/v1/status' \
  $IMAGE >/dev/null && docker image prune -f >/dev/null"

if [ "$RECREATE_MOTIS" = 1 ] || ! $DEPLOY_SSH "docker inspect Takt-MOTIS >/dev/null 2>&1"; then
  echo "== container Takt-MOTIS ($MOTIS_VERSION)"
  $DEPLOY_SSH "docker pull -q ghcr.io/motis-project/motis:$MOTIS_VERSION >/dev/null && docker rm -f Takt-MOTIS >/dev/null 2>&1; \
    docker run -d --name Takt-MOTIS --user=99:100 --restart=unless-stopped --cap-drop=ALL --security-opt=no-new-privileges --memory=8g \
    --entrypoint=sh -p $MOTIS_PORT:8080 -v $DATA/motis:/data -e TZ=Europe/Berlin \
    -l net.unraid.docker.managed=dockerman \
    -l net.unraid.docker.icon=https://raw.githubusercontent.com/motis-project/motis/master/ui/static/favicon.png \
    -l 'net.unraid.docker.webui=http://[IP]:[PORT:8080]/' \
    ghcr.io/motis-project/motis:$MOTIS_VERSION /data/run.sh >/dev/null"
fi
echo "Done: http://$DEPLOY_HOST:$API_PORT/api/v1/status"
