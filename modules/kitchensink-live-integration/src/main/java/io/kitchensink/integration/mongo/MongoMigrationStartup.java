package io.kitchensink.integration.mongo;

import io.kitchensink.migration.mongo.MongoMigrations;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.stereotype.Component;

@Component
public final class MongoMigrationStartup {
    private final MongoConnectionDetails connection;

    public MongoMigrationStartup(MongoConnectionDetails connection) {
        this.connection = connection;
    }

    @PostConstruct
    void migrate() throws Exception {
        MongoMigrations.migrate(connection.getConnectionString());
    }
}
