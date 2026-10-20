#!/bin/sh
. "$(dirname "$0")/common.sh"
tool status "$LAB_ROOT" "$WORKER_PORT" "$CLIENT_PORT"
