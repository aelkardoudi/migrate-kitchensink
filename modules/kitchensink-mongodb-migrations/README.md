# Kitchensink MongoDB Migrations

This Java 21 library owns the MongoDB Liquibase changelog. The live integration module calls `MongoMigrations.migrate(ConnectionString)` at startup. Other applications can depend on this library to apply the same changelog. The connection string must include the target database, and the account needs permission to manage Liquibase history and locks and apply the changesets.

Build from the repository root.

```sh
mvn -P live-integration -pl modules/kitchensink-mongodb-migrations -am clean verify
```

Keep applied changesets and their changelog path stable. Add future changesets to [`db.changelog-master.xml`](src/main/resources/db/changelog/mongodb/db.changelog-master.xml) with new IDs. Liquibase records applied changesets in MongoDB's `DATABASECHANGELOG` collection.
