FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
COPY --chown=1000:1000 wildfly/ /opt/wildfly/
COPY --chown=1000:1000 app/ /opt/wildfly/standalone/deployments/kitchensink.war/
COPY --chmod=755 start.sh /opt/start.sh
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["/opt/start.sh"]
