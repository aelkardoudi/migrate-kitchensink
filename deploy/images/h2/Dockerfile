FROM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS trigger-build
WORKDIR /src
COPY h2.jar MemberInsertTrigger.java ./
RUN javac --release 21 -cp h2.jar -d /opt/trigger MemberInsertTrigger.java

FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
COPY h2.jar /opt/h2.jar
COPY --from=trigger-build /opt/trigger /opt/trigger
COPY schema.sql live-integration.sql /opt/
COPY --chmod=755 start.sh /opt/start.sh
USER 1000:1000
EXPOSE 9092
ENTRYPOINT ["/opt/start.sh"]
