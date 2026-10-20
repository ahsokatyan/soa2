#!/bin/sh
. "$(dirname "$0")/common.sh"
if [ ! -f "$DOMAIN_DIR/config/domain.xml" ]; then
    printf '%s\n' 'Run sh bin/setup.sh first.' >&2
    exit 1
fi
tool check-db "$LAB_ROOT"
tool ports "$LAB_ROOT" "$WORKER_PORT" "$CLIENT_PORT" "$ADMIN_PORT" "$PORT_OFFSET"
# Refresh the old generated deployment while the domain is stopped. Keep a
# recoverable backup to avoid Windows JAR locks during local validation too.
tool prepare-deploy "$LAB_ROOT"
admin start-domain --domaindir "$LAB_ROOT/runtime/payara-domains" soa
admin deploy --force=true --name workers --contextroot workers "$LAB_ROOT/apps/workers.war"
tool wait-worker "$LAB_ROOT" "$WORKER_PORT" "$CLIENT_PORT"
cp "$LAB_ROOT/apps/ROOT.war" "$TOMCAT_BASE/webapps/ROOT.war"
nohup sh "$LAB_ROOT/bin/run-tomcat.sh" > "$LAB_ROOT/runtime/logs/tomcat.out.log" 2>&1 < /dev/null &
printf '%s\n' "$!" > "$LAB_ROOT/runtime/tomcat.pid"
tool wait-client "$LAB_ROOT" "$WORKER_PORT" "$CLIENT_PORT"
printf 'Worker: https://localhost:%s/workers/api/workers\n' "$WORKER_PORT"
printf 'Client: https://localhost:%s/\n' "$CLIENT_PORT"
printf 'Swagger: https://localhost:%s/docs/index.html\n' "$CLIENT_PORT"
