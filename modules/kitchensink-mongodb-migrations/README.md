# Kitchensink MongoDB migrations

This Java 21 module holds the MongoDB Liquibase changelog. Spring Boot calls `MongoMigrations.migrate(ConnectionString)` at startup with a dedicated account that can manage the Liquibase history and locks, create the unique member email index and initialize the member ID counter.

Keep applied changesets and their changelog path stable. Add future changesets with new IDs to [the master changelog](src/main/resources/db/changelog/mongodb/db.changelog-master.xml). Liquibase records applied changesets in MongoDB's `DATABASECHANGELOG` collection.
