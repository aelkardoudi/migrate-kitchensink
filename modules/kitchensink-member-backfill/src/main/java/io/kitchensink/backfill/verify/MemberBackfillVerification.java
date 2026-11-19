package io.kitchensink.backfill.verify;

import com.mongodb.ConnectionString;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.client.MongoClients;

import java.sql.DriverManager;
import java.sql.SQLException;

public final class MemberBackfillVerification {
    private MemberBackfillVerification() {
    }

    public static int run(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -jar kitchensink-member-backfill.jar verify");
            return 2;
        }

        try {
            var mongoUri = new ConnectionString(requiredEnvironment("KS_MONGODB_URI"));
            if (mongoUri.getDatabase() == null) {
                System.err.println("KS_MONGODB_URI must specify the target database");
                return 2;
            }

            try (var source = DriverManager.getConnection(requiredEnvironment("KS_H2_JDBC_URL"),
                    System.getenv().getOrDefault("KS_H2_USER", "members_backfill"), requiredEnvironment("KS_H2_PASSWORD"));
                 var mongo = MongoClients.create(mongoUri)) {
                source.setReadOnly(true);
                var target = mongo.getDatabase(mongoUri.getDatabase()).getCollection("members")
                        .withReadConcern(ReadConcern.MAJORITY);
                var counts = MemberCountVerification.verifyTotals(source, target);
                System.out.printf("Verification %s h2=%d mongo=%d%n",
                        counts.match() ? "COUNT_MATCH" : "COUNT_MISMATCH", counts.h2(), counts.mongo());
                return counts.match() ? 0 : 1;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("Verification interrupted");
        } catch (SQLException exception) {
            System.err.printf("H2 verification read failed (SQL state %s)%n", exception.getSQLState());
        } catch (MongoException | IllegalArgumentException exception) {
            System.err.printf("MongoDB verification failed (%s)%n", exception.getClass().getSimpleName());
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage());
        }
        return 2;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

}
