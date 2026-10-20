#!/bin/sh
# Sourced by the scripts in this bin directory; not a standalone command.
set -eu
umask 077
LAB_ROOT=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
. "$LAB_ROOT/config/helios.conf"
# Helios supplies a 128 MB metaspace limit via _JAVA_OPTIONS. Remove it only
# in this script and its children; account-wide shell settings are untouched.
unset _JAVA_OPTIONS
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p' | head -n 1)
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    printf '%s\n' 'Java not found. Set JAVA_HOME in config/helios.conf.' >&2
    exit 1
fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
PAYARA_DIR="$LAB_ROOT/servers/payara6"
DOMAIN_DIR="$LAB_ROOT/runtime/payara-domains/soa"
TOMCAT_DIR="$LAB_ROOT/servers/apache-tomcat-10.1.61"
TOMCAT_BASE="$LAB_ROOT/runtime/tomcat"
export SOA_PUBLIC_WORKER_URL SOA_PUBLIC_HR_URL SOA_CLIENT_ORIGINS SOA_ORGANIZATIONS
SOA_WORKER_URL="https://localhost:$WORKER_PORT/workers/api"
SOA_JDBC_PASSWORD_FILE="$LAB_ROOT/runtime/secrets/db-password"
unset SOA_JDBC_PASSWORD
SOA_TRUSTSTORE="$LAB_ROOT/runtime/certs/truststore.p12"
export SOA_WORKER_URL SOA_JDBC_URL SOA_JDBC_USER SOA_JDBC_SCHEMA SOA_JDBC_PASSWORD_FILE SOA_JDBC_DRIVER SOA_TRUSTSTORE
if [ -f "$LAB_ROOT/runtime/secrets/internal-token" ]; then
    SOA_INTERNAL_TOKEN=$(cat "$LAB_ROOT/runtime/secrets/internal-token")
    SOA_STORE_PASSWORD=$(cat "$LAB_ROOT/runtime/secrets/store-password")
    export SOA_INTERNAL_TOKEN SOA_STORE_PASSWORD
fi
tool() {
    "$JAVA" -Xms16m -Xmx96m -XX:MaxMetaspaceSize=96m -cp "$LAB_ROOT/tools:$LAB_ROOT/tools/lib/*" DeployTool "$@"
}
admin() {
    "$JAVA" -Xms16m -Xmx96m -XX:MaxMetaspaceSize=96m \
        -Duser.home="$LAB_ROOT/runtime/admin-home" \
        -jar "$PAYARA_DIR/glassfish/lib/client/appserver-cli.jar" \
        --host 127.0.0.1 --port "$ADMIN_PORT" --interactive=false "$@"
}
