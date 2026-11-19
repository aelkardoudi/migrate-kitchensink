package io.kitchensink.backfill.verify;

import org.bson.BsonInt64;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberCountVerificationTest {
    @Test
    void countsOnlyMissingQueuedMembersWithinTheInclusiveBound() throws SQLException {
        try (var source = DriverManager.getConnection("jdbc:h2:mem:bounded-counts")) {
            createSchema(source);
            try (var statement = source.createStatement()) {
                statement.execute("INSERT INTO MEMBER VALUES (0), (10), (20), (50)");
                statement.execute("INSERT INTO MEMBER_INSERT_OUTBOX VALUES (10), (15), (20), (50)");
            }

            var counts = MemberCountVerification.check(source,
                    filter -> countMongo(List.of(0L, 10L, 200L), filter), 20L);

            assertEquals(3L, counts.h2());
            assertEquals(2L, counts.mongo());
            assertEquals(1L, counts.pendingMissing());
            assertTrue(counts.stable());
            assertFalse(counts.match());
            assertTrue(counts.explainedByOutbox());
        }
    }

    @Test
    void detectsAMissingHistoricalMemberEvenWhenTheOutboxIsNonempty() throws SQLException {
        try (var source = DriverManager.getConnection("jdbc:h2:mem:unexplained-counts")) {
            createSchema(source);
            try (var statement = source.createStatement()) {
                statement.execute("INSERT INTO MEMBER VALUES (0), (10), (20)");
                statement.execute("INSERT INTO MEMBER_INSERT_OUTBOX VALUES (10), (20)");
            }

            var counts = MemberCountVerification.check(source, filter -> countMongo(List.of(10L), filter), 20L);

            assertEquals(3L, counts.h2());
            assertEquals(1L, counts.mongo());
            assertEquals(1L, counts.pendingMissing());
            assertTrue(counts.stable());
            assertFalse(counts.match());
            assertFalse(counts.explainedByOutbox());
        }
    }

    @Test
    void checksMembersBeyondTheBackfillBoundAndRequiresTheirOutboxEntries() throws SQLException {
        try (var source = DriverManager.getConnection("jdbc:h2:mem:whole-table-counts");
             var statement = source.createStatement()) {
            createSchema(source);
            statement.execute("INSERT INTO MEMBER VALUES (0), (42)");
            var mongo = new ArrayList<>(List.of(0L));

            var bounded = MemberCountVerification.check(source, filter -> countMongo(mongo, filter), 0L);
            assertTrue(bounded.match());

            var wholeTable = MemberCountVerification.check(source, filter -> countMongo(mongo, filter), null);
            assertEquals(2L, wholeTable.h2());
            assertEquals(1L, wholeTable.mongo());
            assertFalse(wholeTable.match());
            assertFalse(wholeTable.explainedByOutbox());

            statement.execute("INSERT INTO MEMBER_INSERT_OUTBOX VALUES (42)");
            var queued = MemberCountVerification.check(source, filter -> countMongo(mongo, filter), null);
            assertEquals(1L, queued.pendingMissing());
            assertTrue(queued.explainedByOutbox());

            mongo.add(42L);
            var copied = MemberCountVerification.check(source, filter -> countMongo(mongo, filter), null);
            assertEquals(0L, copied.pendingMissing());
            assertTrue(copied.match());
        }
    }

    @Test
    void marksTheObservationUnstableWhenCamelWritesDuringTheCount() throws SQLException {
        try (var source = DriverManager.getConnection("jdbc:h2:mem:moving-counts")) {
            createSchema(source);
            try (var statement = source.createStatement()) {
                statement.execute("INSERT INTO MEMBER VALUES (0), (10)");
                statement.execute("INSERT INTO MEMBER_INSERT_OUTBOX VALUES (10)");
            }
            var mongo = new ArrayList<>(List.of(0L));

            var counts = MemberCountVerification.check(source, filter -> {
                long count = countMongo(mongo, filter);
                if (filter.toBsonDocument().getDocument("_id").containsKey("$in")) {
                    mongo.add(10L);
                }
                return count;
            }, 10L);

            assertFalse(counts.stable());
            assertFalse(counts.match());
            assertFalse(counts.explainedByOutbox());
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE MEMBER (ID BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE MEMBER_INSERT_OUTBOX (MEMBER_ID BIGINT PRIMARY KEY)");
        }
    }

    private static long countMongo(List<Long> ids, Bson filter) {
        var query = filter.toBsonDocument();
        if (query.isEmpty()) {
            return ids.size();
        }
        var id = query.getDocument("_id");
        if (id.containsKey("$lte")) {
            long upperBound = id.getInt64("$lte").getValue();
            return ids.stream().filter(memberId -> memberId <= upperBound).count();
        }
        var requested = id.getArray("$in");
        return ids.stream().filter(memberId -> requested.contains(new BsonInt64(memberId))).count();
    }
}
