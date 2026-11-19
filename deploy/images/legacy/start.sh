#!/bin/sh
set -eu
touch /opt/wildfly/standalone/deployments/kitchensink.war.dodeploy
exec /opt/wildfly/bin/standalone.sh -b 0.0.0.0
