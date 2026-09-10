#!/usr/bin/env bash
# DBMS launcher (macOS / Linux)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/target/dbms-1.0.0.jar"

build_jar() {
  if [ ! -f "$JAR" ]; then
    echo "==> building jar ..."
    ( cd "$ROOT" && mvn -q -DskipTests package )
  fi
}

cmd="${1:-usage}"
shift || true

case "$cmd" in
  build)   build_jar; echo "OK: $JAR" ;;
  create)  build_jar; java -cp "$JAR" com.course.dbms.server.ServerLauncher create "${1:-data/db}" 9999 ;;
  open)    build_jar; java -cp "$JAR" com.course.dbms.server.ServerLauncher open "${1:-data/db}" 9999 ;;
  client)  build_jar; java -cp "$JAR" com.course.dbms.client.ClientLauncher "$@" ;;
  demo)
    build_jar
    rm -rf "$ROOT/data/db"
    echo "==> starting server (create data/db, 9999)"
    java -cp "$JAR" com.course.dbms.server.ServerLauncher create data/db 9999 >"$ROOT/target/demo-server.log" 2>&1 &
    SRV=$!
    sleep 2
    echo "==> running DEMO.sql"
    java -cp "$JAR" com.course.dbms.client.ClientLauncher -f "$ROOT/DEMO.sql" || true
    kill "$SRV" 2>/dev/null || true
    echo "==> server stopped. log: target/demo-server.log"
    ;;
  test)    ( cd "$ROOT" && mvn -q test ) ;;
  *) echo "usage: run.sh <create|open|client|demo|test> [args]" ;;
esac
