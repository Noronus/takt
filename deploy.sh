#!/bin/bash
# Builds the Takt image locally, loads it on the Docker host (Unraid) via SSH, installs the
# two Unraid templates and (re)creates the containers Takt and Takt-MOTIS. Data is kept.
# Usage: ./deploy.sh            update Takt (MOTIS only if missing)
#        ./deploy.sh --motis    also recreate Takt-MOTIS (e.g. after a MOTIS version change)
#        ./deploy.sh --seed DIR copy prepared data from DIR (cache/, static/, motis/input/) first
set -euo pipefail
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "deploy.env missing, see deploy.env.example"; exit 1; }
source deploy.env
: "${DEPLOY_SSH:?}" "${DEPLOY_HOST:?}"
API_PORT=${API_PORT:-8097}; MOTIS_PORT=${MOTIS_PORT:-8098}; DATA=${DATA:-/mnt/user/appdata/takt}; MOTIS_VERSION=${MOTIS_VERSION:-2.11}
RECREATE_MOTIS=0; SEED=""
while [ $# -gt 0 ]; do case "$1" in --motis) RECREATE_MOTIS=1;; --seed) SEED="$2"; shift;; esac; shift; done

render() {
  sed -e "s#__HOST__#$DEPLOY_HOST#g" -e "s#__API_PORT__#$API_PORT#g" -e "s#__MOTIS_PORT__#$MOTIS_PORT#g" \
      -e "s#__MOTIS_VERSION__#$MOTIS_VERSION#g" -e "s#/mnt/user/appdata/takt#$DATA#g" "$1"
}

echo "== build image"
docker build -q -t takt:latest . >/dev/null
echo "== upload image"
docker save takt:latest | gzip -1 | $DEPLOY_SSH 'gunzip | docker load' >/dev/null

echo "== templates + data folders"
T=/boot/config/plugins/dockerMan/templates-user
render unraid/my-Takt.xml | $DEPLOY_SSH "cat > $T/my-Takt.xml"
render unraid/my-Takt-MOTIS.xml | $DEPLOY_SSH "cat > $T/my-Takt-MOTIS.xml"
$DEPLOY_SSH "mkdir -p $DATA/motis && chown -R 99:100 $DATA"

if [ -n "$SEED" ]; then
  echo "== seed data from $SEED"
  tar -C "$SEED" -cf - --exclude='cache/osm' --exclude='motis/build-*' --exclude='motis/current' --exclude='motis/run.sh' \
      cache static motis/input state.json | $DEPLOY_SSH "tar -C $DATA -xf - && chown -R 99:100 $DATA"
fi

echo "== container Takt"
$DEPLOY_SSH "docker rm -f Takt >/dev/null 2>&1; docker run -d --name Takt --user=99:100 --restart=unless-stopped \
  --cap-drop=ALL --security-opt=no-new-privileges --memory=4g \
  -p $API_PORT:8080 -v $DATA:/data -e TZ=Europe/Berlin \
  -e MOTIS_URL=http://$DEPLOY_HOST:$MOTIS_PORT -e SELF_URL=http://$DEPLOY_HOST:$API_PORT \
  -l net.unraid.docker.managed=dockerman -l net.unraid.docker.icon=http://$DEPLOY_HOST:$API_PORT/icon.png \
  -l 'net.unraid.docker.webui=http://[IP]:[PORT:8080]/api/v1/status' \
  takt:latest >/dev/null && docker image prune -f >/dev/null"

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
