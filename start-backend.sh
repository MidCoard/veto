#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: sh start-backend.sh <built-jar>" >&2
    exit 2
fi

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
jar_dir=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd -P)
jar_path="$jar_dir/$(basename -- "$1")"
if [ ! -r "$jar_path" ] || [ -d "$jar_path" ]; then
    echo "JAR is not a readable file: $jar_path" >&2
    exit 2
fi

if [ -n "${JAVA_HOME:-}" ]; then
    java="$JAVA_HOME/bin/java"
else
    java=$(command -v java) || { echo "Java is required (JAVA_HOME or PATH)." >&2; exit 2; }
fi
if [ ! -x "$java" ]; then
    echo "Java is not executable: $java" >&2
    exit 2
fi

cd "$repo_root"
runtime_root="$repo_root/work/tmp/backend-runtime"
mkdir -p "$runtime_root"
runtime_dir=$(mktemp -d "$runtime_root/launch.XXXXXXXX")
cp "$jar_path" "$runtime_dir/veto.jar"
nohup "$java" --enable-native-access=ALL-UNNAMED \
    "-Djava.io.tmpdir=$runtime_dir" \
    "-Djdk.net.unixdomain.tmpdir=$runtime_dir" \
    "-Dveto.observability.audit-log-path=$repo_root/audit" \
    -jar "$runtime_dir/veto.jar" \
    >"$runtime_dir/out.log" 2>"$runtime_dir/err.log" </dev/null &
echo "Backend process started: PID=$!; cwd=$repo_root; audit=$repo_root/audit"
echo "Startup logs: $runtime_dir/out.log (check readiness before use)"
