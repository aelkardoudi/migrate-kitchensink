package io.kitchensink.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.util.Objects;

public final class MongoMemberStore {
    private static final UpdateOptions UPSERT = new UpdateOptions().upsert(true);

    private final MongoCollection<Document> writes;
    private final MongoCollection<Document> majorityReads;

    public MongoMemberStore(MongoCollection<Document> members) {
        writes = members.withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
        majorityReads = members.withReadConcern(ReadConcern.MAJORITY);
    }

    public void insertIfAbsent(long id, String name, String email, String phoneNumber) {
        var key = new Document("_id", id);
        var fields = new Document("name", name).append("email", email).append("phoneNumber", phoneNumber);

        MongoWriteException duplicate = null;
        try {
            // Use only the unique key in the filter and let MongoDB include it in the inserted document.
            if (writes.updateOne(key, new Document("$setOnInsert", fields), UPSERT).getUpsertedId() != null) {
                return;
            }
        } catch (MongoWriteException failure) {
            if (failure.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw failure;
            }
            duplicate = failure;
        }

        // A concurrent writer or an earlier attempt may have inserted the member already.
        var existing = majorityReads.find(key).first();
        if (sameMember(existing, id, fields)) {
            return;
        }
        if (duplicate != null) {
            // MongoDB includes the duplicate key value, potentially an email address, in the exception.
            throw new IllegalStateException("MongoDB rejected member %d (MongoWriteException %d)"
                    .formatted(id, duplicate.getError().getCode()));
        }
        if (existing == null) {
            throw new IllegalStateException("Member " + id + " is not majority committed in MongoDB");
        }
        throw new IllegalStateException("MongoDB already contains different data for member " + id);
    }

    private static boolean sameMember(Document existing, Long id, Document fields) {
        return existing != null && id.equals(existing.get("_id")) && fields.entrySet().stream()
                .allMatch(field -> existing.containsKey(field.getKey())
                        && Objects.equals(field.getValue(), existing.get(field.getKey())));
    }
}
