#!/bin/sh
# Builds the plugin, starts a throw-away Jenkins in Docker with sample data and prints how to log in.
#   usage: demo/run-demo.sh [port]          (default 8080; stop with: docker rm -f hygiene-auditor-demo)
set -eu
cd "$(dirname "$0")/.."
PORT="${1:-8080}"
NAME=hygiene-auditor-demo

[ -f target/hygiene-auditor.hpi ] || mvn -q -DskipTests package      # needs a JDK 21 as JAVA_HOME
cp target/hygiene-auditor.hpi demo/hygiene-auditor.hpi
docker build -q -t hygiene-auditor-demo:dev demo
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "$PORT:8080" hygiene-auditor-demo:dev >/dev/null

URL="http://localhost:$PORT"
printf 'waiting for Jenkins'
until curl -s -o /dev/null -w '%{http_code}' -u admin:admin "$URL/login" | grep -q 200; do printf .; sleep 3; done; echo

JAR="$(mktemp)"
script() {  # run a Groovy file in the script console (demo only; needs admin)
  CRUMB="$(curl -s -c "$JAR" -b "$JAR" -u admin:admin "$URL/crumbIssuer/api/json" | sed -E 's/.*"crumb":"([^"]+)".*/\1/')"
  curl -s -c "$JAR" -b "$JAR" -u admin:admin -H "Jenkins-Crumb: $CRUMB" --data-urlencode "script@$1" "$URL/scriptText"
}
script demo/seed-1-orphan.groovy
sleep 15
until curl -s -o /dev/null -w '%{http_code}' -u admin:admin "$URL/login" | grep -q 200; do sleep 3; done
script demo/seed-2-main.groovy

echo
echo "Open $URL/manage/hygiene-auditor/  (admin / admin)"
echo "Folder-only view:   user 'lead' / 'lead' at $URL/job/legacy/hygiene-auditor/"
