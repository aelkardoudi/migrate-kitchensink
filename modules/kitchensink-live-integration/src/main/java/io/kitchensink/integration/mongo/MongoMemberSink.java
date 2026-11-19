package io.kitchensink.integration.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.UpdateOptions;
import io.kitchensink.integration.model.MemberInsert;
import org.bson.Document;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
@DependsOn("mongoMigrationStartup")
public final class MongoMemberSink implements Consumer<MemberInsert> {
    private static final Collation BINARY_COLLATION = Collation.builder().locale("simple").build();
    private static final UpdateOptions INSERT_IF_ABSENT = new UpdateOptions().upsert(true).collation(BINARY_COLLATION);

    private final MongoCollection<Document> members;

    public MongoMemberSink(MongoClient client, MongoConnectionDetails connection) {
        members = client.getDatabase(connection.getConnectionString().getDatabase())
                .getCollection("members").withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
    }

    @Override
    public void accept(MemberInsert event) {
        var document = new Document("_id", event.id())
                .append("name", event.name())
                .append("email", event.email())
                .append("phoneNumber", event.phoneNumber());

        try {
            // An identical replay matches. Different data with the same ID hits MongoDB's unique _id index.
            var result = members.updateOne(document, new Document("$setOnInsert", document), INSERT_IF_ABSENT);
            if (result.getUpsertedId() == null && members.withReadConcern(ReadConcern.MAJORITY)
                    .find(document).collation(BINARY_COLLATION).first() == null) {
                // An earlier insert may still be uncommitted after a write concern timeout.
                throw new IllegalStateException("Member " + event.id() + " is not majority committed in MongoDB");
            }
        } catch (MongoWriteException failure) {
            if (failure.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw failure;
            }
            // Duplicate index errors contain the raw key, which can be the member's email address.
            throw new IllegalStateException("MongoDB rejected member %d (MongoWriteException %d)"
                    .formatted(event.id(), failure.getError().getCode()));
        }
    }
}
