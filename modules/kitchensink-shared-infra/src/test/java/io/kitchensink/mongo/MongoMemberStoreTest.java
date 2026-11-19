package io.kitchensink.mongo;

import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoMemberStoreTest {
    private static final Document KEY = new Document("_id", 42L);
    private static final String EMAIL = "member@example.test";

    private final MongoCollection<Document> source = mock();
    private final MongoCollection<Document> writes = mock();
    private final MongoCollection<Document> majorityReads = mock();
    private final FindIterable<Document> byId = mock();
    private MongoMemberStore store;

    @BeforeEach
    void setUp() {
        when(source.withWriteConcern(WriteConcern.MAJORITY.withJournal(true))).thenReturn(writes);
        when(source.withReadConcern(ReadConcern.MAJORITY)).thenReturn(majorityReads);
        when(majorityReads.find(KEY)).thenReturn(byId);
        store = new MongoMemberStore(source);
    }

    @Test
    void acceptsRacingDuplicateAfterAnIdenticalMajorityCommittedWrite() {
        rejectDuplicate();
        when(byId.first()).thenReturn(member("Member").append("additionalField", "preserved"));

        assertDoesNotThrow(() -> store.insertIfAbsent(42, "Member", EMAIL, null));
    }

    @Test
    void keepsDuplicateFailureFromAnotherMemberWithTheSameEmailWithoutLeakingTheKey() {
        rejectDuplicate();
        when(byId.first()).thenReturn(null);

        var failure = assertThrows(IllegalStateException.class,
                () -> store.insertIfAbsent(42, "Member", EMAIL, null));
        assertFalse(failure.toString().contains(EMAIL));
    }

    @Test
    void failsWhenAnExistingIdHasDifferentFields() {
        when(writes.updateOne(eq(KEY), any(Document.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 0L, null));
        when(byId.first()).thenReturn(member("Different member"));

        assertThrows(IllegalStateException.class, () -> store.insertIfAbsent(42, "Member", EMAIL, null));
    }

    private void rejectDuplicate() {
        var duplicate = new MongoWriteException(new WriteError(11000, "Duplicate email " + EMAIL, new BsonDocument()),
                new ServerAddress(), Set.of());
        when(writes.updateOne(eq(KEY), any(Document.class), any(UpdateOptions.class))).thenThrow(duplicate);
    }

    private static Document member(String name) {
        return new Document("_id", 42L).append("name", name).append("email", EMAIL).append("phoneNumber", null);
    }
}
