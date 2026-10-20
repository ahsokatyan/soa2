#!/bin/sh
. "$(dirname "$0")/common.sh"
tool smoke "$LAB_ROOT" "$WORKER_PORT" "$CLIENT_PORT"
