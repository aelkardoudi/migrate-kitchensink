FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
COPY --chown=1000:1000 kitchensink-member-backfill.jar /app/kitchensink-member-backfill.jar
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app/kitchensink-member-backfill.jar"]
