#!/bin/sh
set -eu
: "${H2_MAINT_PASSWORD:?}" "${H2_APP_PASSWORD:?}"
case "$H2_MAINT_PASSWORD$H2_APP_PASSWORD" in *[!a-f0-9]*) echo 'Expected generated hex credentials' >&2; exit 2;; esac
# Safe to retry after a crash during initial schema creation.
umask 077
setup=$(mktemp)
trap 'rm -f "$setup"' EXIT
cat /opt/schema.sql > "$setup"
printf "CREATE USER IF NOT EXISTS members PASSWORD '%s';\nGRANT SELECT, INSERT ON Member TO members;\n" "$H2_APP_PASSWORD" >> "$setup"
cat /opt/live-integration.sql >> "$setup"
classpath=/opt/h2.jar:/opt/trigger
java -cp "$classpath" org.h2.tools.RunScript -url jdbc:h2:file:/data/kitchensink -user maintenance -password "$H2_MAINT_PASSWORD" -script "$setup"
rm -f "$setup"
exec java -Xms32m -Xmx128m -cp "$classpath" org.h2.tools.Server -tcp -tcpAllowOthers -tcpPort 9092 -ifExists -baseDir /data
