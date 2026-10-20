#!/bin/sh
. "$(dirname "$0")/common.sh"
tool verify "$LAB_ROOT"
if [ ! -f "$SOA_JDBC_PASSWORD_FILE" ]; then
    tool db-password "$LAB_ROOT"
fi
tool check-db "$LAB_ROOT"
mkdir -p "$LAB_ROOT/servers"
if [ ! -f "$PAYARA_DIR/glassfish/lib/client/appserver-cli.jar" ]; then
    tar -xf "$LAB_ROOT/distributions/payara-6.2025.11.zip" -C "$LAB_ROOT/servers"
fi
if [ ! -f "$TOMCAT_DIR/bin/bootstrap.jar" ]; then
    tar -xf "$LAB_ROOT/distributions/apache-tomcat-10.1.61.zip" -C "$LAB_ROOT/servers"
fi
tool configure "$LAB_ROOT" "$WORKER_PORT" "$CLIENT_PORT" "$ADMIN_PORT" "$PORT_OFFSET"
printf '%s\n' 'Setup complete. Next: sh bin/start.sh'
