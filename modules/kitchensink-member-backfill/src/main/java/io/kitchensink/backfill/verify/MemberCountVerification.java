package io.kitchensink.backfill.verify;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.CountOptions;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.ToLongFunction;

import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lte;

final class MemberCountVerification {
    private static final int MAX_ATTEMPTS = 5;
    private static final int OUTBOX_PAGE_SIZE = 500;
    private static final String OUTBOX_PAGE = """
            SELECT o.MEMBER_ID
            FROM PUBLIC.MEMBER_INSERT_OUTBOX o JOIN PUBLIC.MEMBER m ON m.ID = o.MEMBER_ID
            WHERE o.MEMBER_ID >= ? AND o.MEMBER_ID <= ?
            ORDER BY o.MEMBER_ID
            FETCH FIRST ? ROWS ONLY
            """;

    private MemberCountVerification() {
    }

    static Counts verify(Connection source, MongoCollection<Document> target, Long maxMemberId)
            throws SQLException, InterruptedException {
        return verify(source, target, maxMemberId, true);
    }

    static Counts verifyTotals(Connection source, MongoCollection<Document> target)
            throws SQLException, InterruptedException {
        return verify(source, target, null, false);
    }

    private static Counts verify(Connection source, MongoCollection<Document> target, Long maxMemberId,
                                 boolean reconcileOutbox) throws SQLException, InterruptedException {
        var countOptions = new CountOptions().hintString("_id_");
        ToLongFunction<Bson> countTarget = filter -> target.countDocuments(filter, countOptions);
        for (int attempt = 1; ; attempt++) {
            var counts = reconcileOutbox ? check(source, countTarget, maxMemberId)
                    : new Counts(countSource(source, null), countTarget.applyAsLong(new BsonDocument()), 0, true);
            if (counts.match() || counts.explainedByOutbox() || attempt == MAX_ATTEMPTS) {
                return counts;
            }
            Thread.sleep(Duration.ofSeconds(1));
        }
    }

    static Counts check(Connection source, ToLongFunction<Bson> countTarget, Long maxMemberId) throws SQLException {
        Bson scope = maxMemberId == null ? new BsonDocument() : lte("_id", maxMemberId);
        long h2 = countSource(source, maxMemberId);
        long mongo = countTarget.applyAsLong(scope);
        long pendingMissing = 0;
        boolean stableMongo = true;
        if (h2 > mongo) {
            pendingMissing = countMissingOutboxMembers(source, countTarget,
                    maxMemberId == null ? Long.MAX_VALUE : maxMemberId);
            stableMongo = countTarget.applyAsLong(scope) == mongo;
        }
        // Member data is append-only. Stable counts keep a concurrent insert from explaining an older gap.
        boolean stableH2 = countSource(source, maxMemberId) == h2;
        return new Counts(h2, mongo, pendingMissing, stableH2 && stableMongo);
    }

    static long countSource(Connection source, Long maxMemberId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM PUBLIC.MEMBER" + (maxMemberId == null ? "" : " WHERE ID <= ?");
        try (var statement = source.prepareStatement(sql)) {
            if (maxMemberId != null) {
                statement.setLong(1, maxMemberId);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long countMissingOutboxMembers(Connection source, ToLongFunction<Bson> countTarget, long maxMemberId)
            throws SQLException {
        long missing = 0;
        long firstId = Long.MIN_VALUE;
        try (var statement = source.prepareStatement(OUTBOX_PAGE)) {
            statement.setLong(2, maxMemberId);
            statement.setInt(3, OUTBOX_PAGE_SIZE);
            while (true) {
                statement.setLong(1, firstId);
                var ids = new ArrayList<Long>(OUTBOX_PAGE_SIZE);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                    }
                }
                if (ids.isEmpty()) {
                    return missing;
                }
                missing += ids.size() - countTarget.applyAsLong(in("_id", ids));
                if (ids.size() < OUTBOX_PAGE_SIZE || ids.getLast() == maxMemberId) {
                    return missing;
                }
                firstId = ids.getLast() + 1;
            }
        }
    }

    record Counts(long h2, long mongo, long pendingMissing, boolean stable) {
        boolean match() {
            return stable && h2 == mongo;
        }

        boolean explainedByOutbox() {
            return stable && h2 > mongo && h2 - mongo == pendingMissing;
        }
    }
}
