#!/usr/bin/env sh
# Starts Llama and starts it again whenever it exits with code 75 (restart requested by the server).
# If the server asks for more than MAX_RESTARTS restarts within RESTART_WINDOW seconds, it stays off to avoid a
# crash loop.
#
# Environment:
#   LLAMA_JAR     path to the server jar          (default: llama.jar next to this script)
#   LLAMA_MEMORY  heap size for -Xms/-Xmx          (default: 2G)
#   JAVA          java executable                  (default: java)
#   JAVA_FLAGS    extra JVM flags                  (default: ZGC and compact object headers)

cd "$(dirname "$0")" || exit 1

JAR="${LLAMA_JAR:-llama.jar}"
MEMORY="${LLAMA_MEMORY:-2G}"
JAVA="${JAVA:-java}"
JAVA_FLAGS="${JAVA_FLAGS:--XX:+UseZGC -XX:+UseCompactObjectHeaders}"
RESTART_CODE=75
MAX_RESTARTS=3
RESTART_WINDOW=300

restarts=""
while true; do
    # shellcheck disable=SC2086
    "$JAVA" -Xms"$MEMORY" -Xmx"$MEMORY" $JAVA_FLAGS -jar "$JAR" "$@"
    code=$?
    if [ "$code" -ne "$RESTART_CODE" ]; then
        exit "$code"
    fi

    now=$(date +%s)
    recent=""
    count=0
    for time in $restarts; do
        if [ $((now - time)) -lt "$RESTART_WINDOW" ]; then
            recent="$recent $time"
            count=$((count + 1))
        fi
    done
    if [ "$count" -ge "$MAX_RESTARTS" ]; then
        echo "Llama requested $((count + 1)) restarts within $RESTART_WINDOW seconds; not restarting again." >&2
        echo "Check crash-reports/ and logs/latest.log." >&2
        exit 1
    fi
    restarts="$recent $now"
    echo "Restarting Llama..."
    sleep 2
done
