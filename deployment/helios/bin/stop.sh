#!/bin/sh
. "$(dirname "$0")/common.sh"
tool stop-tomcat "$LAB_ROOT"
admin stop-domain --domaindir "$LAB_ROOT/runtime/payara-domains" soa
