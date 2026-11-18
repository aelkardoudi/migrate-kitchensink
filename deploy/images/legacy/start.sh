#!/bin/sh
set -eu
: "${H2_APP_PASSWORD:?}"
case "$H2_APP_PASSWORD" in *[!a-f0-9]*) echo 'Expected generated hex credentials' >&2; exit 2;; esac
ds=/opt/wildfly/standalone/deployments/kitchensink.war/WEB-INF/kitchensink-quickstart-ds.xml
sed -i "s/__H2_APP_PASSWORD__/$H2_APP_PASSWORD/g" "$ds"
touch /opt/wildfly/standalone/deployments/kitchensink.war.dodeploy
exec /opt/wildfly/bin/standalone.sh -b 0.0.0.0
