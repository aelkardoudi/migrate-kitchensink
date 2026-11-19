package io.kitchensink.migration.mongo;

import java.util.Map;
import java.util.Properties;

import com.mongodb.ConnectionString;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.database.DatabaseFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

public final class MongoMigrations {
    private static final String CHANGELOG = "db/changelog/mongodb/db.changelog-master.xml";

    private MongoMigrations() {
    }

    public static void migrate(ConnectionString connectionString) throws Exception {
        if (connectionString.getDatabase() == null) {
            throw new IllegalArgumentException("MongoDB connection string must name a database");
        }

        var driverProperties = new Properties();
        // Liquibase MongoDB's version discovery can fail inside a Spring Boot nested JAR.
        driverProperties.setProperty("appName", "kitchensink-mongodb-migrations");

        try (var resources = new ClassLoaderResourceAccessor(MongoMigrations.class.getClassLoader())) {
            Scope.child(Map.of("liquibase.analytics.enabled", false), () -> {
                var database = DatabaseFactory.getInstance()
                        .openDatabase(connectionString.getConnectionString(), null, null, null, driverProperties, resources);
                try (var liquibase = new Liquibase(CHANGELOG, resources, database)) {
                    liquibase.update();
                }
            });
        }
    }
}
