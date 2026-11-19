package io.kitchensink.configuration;

import com.mongodb.ConnectionString;
import io.kitchensink.migration.mongo.MongoMigrations;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.stereotype.Component;

@Component
class MongoMigrationStartup {
    private final MongoConnectionDetails runtime;
    private final String migrationUri;

    MongoMigrationStartup(MongoConnectionDetails runtime,
            @Value("${kitchensink.mongodb.migration-uri}") String migrationUri) {
        this.runtime = runtime;
        this.migrationUri = migrationUri;
    }

    @PostConstruct
    void migrate() throws Exception {
        var migration = new ConnectionString(migrationUri);
        String database = migration.getDatabase();
        if (database == null || !database.equals(runtime.getConnectionString().getDatabase())) {
            throw new IllegalArgumentException("MongoDB migration and runtime connections must name the same database");
        }
        MongoMigrations.migrate(migration);
    }
}
