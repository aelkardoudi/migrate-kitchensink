# Kitchensink Live Integration

This Spring Boot application runs the Apache Camel route that relays new H2 member inserts to MongoDB. The member API continues to use H2. The baseline seed and other members already present when the trigger is installed require a separate historical import.

To deploy the relay and MongoDB on kind, follow the [Kubernetes deployment guide](../../deploy/README.md#deploy-mongodb-and-the-camel-relay).

1. Build and test the relay with JDK 21 from the repository root.

   ```sh
   mvn -P live-integration -pl modules/kitchensink-live-integration -am clean verify
   ```

2. Build the updated H2 image with `./deploy/build-images.sh` after packaging the legacy WAR. Start H2 using that image. Its startup script installs the outbox and insert trigger on both new and existing databases. Connect as H2 maintenance and grant a dedicated relay user access to the tables.

   ```sql
   CREATE USER IF NOT EXISTS members_relay PASSWORD '<relay-password>';
   GRANT SELECT ON Member TO members_relay;
   GRANT SELECT, UPDATE, DELETE ON MEMBER_INSERT_OUTBOX TO members_relay;
   ```

3. Set the following environment variables and start the relay. The MongoDB URI must include the target database. Its user must be allowed to manage Liquibase's changelog collections, create the member index and write to `members`.

   | Variable | Example |
   | --- | --- |
   | `KS_H2_JDBC_URL` | `jdbc:h2:tcp://localhost:9092/kitchensink` |
   | `KS_H2_USER` | `members_relay` (default) |
   | `KS_H2_PASSWORD` | The relay user's password |
   | `KS_MONGODB_URI` | `mongodb://localhost:27017/kitchensink` |
   | `KS_RELAY_POLL_INTERVAL_MS` | `200` (default) |

   ```sh
   java -jar modules/kitchensink-live-integration/target/kitchensink-live-integration.jar
   ```

The trigger queues the member ID in the same H2 transaction as the insert. By default, the Camel SQL route starts polling as soon as it starts and waits 200 ms between polls. Delivery latency depends on the interval, backlog and MongoDB write time. Camel deletes each entry from the outbox after MongoDB confirms the write. A failed delivery becomes eligible for retry after 30 seconds. MongoDB uses the H2 ID as `_id` and preserves the first successful insert on replay. To inspect pending work, query `SELECT MEMBER_ID, NEXT_ATTEMPT_AT FROM MEMBER_INSERT_OUTBOX`.

Liquibase applies the changelog from the [MongoDB migrations module](../kitchensink-mongodb-migrations/README.md) at relay startup, before Camel polls H2. The build includes the migration module in the executable relay JAR.

Stopping the relay leaves pending entries in H2. Keep the new H2 image when rolling back Spring Boot or WildFly so inserts continue to be captured. Before reverting the H2 image itself to a build without the trigger class, stop the relay and remove the trigger using the current H2 instance.
