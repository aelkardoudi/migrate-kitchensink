FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
COPY h2.jar /opt/h2.jar
COPY schema.sql /opt/schema.sql
COPY --chmod=755 start.sh /opt/start.sh
USER 1000:1000
EXPOSE 9092
ENTRYPOINT ["/opt/start.sh"]
