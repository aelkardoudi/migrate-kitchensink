package io.kitchensink.adapter.out.mongo;

import org.bson.BsonType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoMemberAdapterTest {
    private final MongoMemberRepository repository = mock(MongoMemberRepository.class);
    private final MongoOperations operations = mock(MongoOperations.class);
    private final MongoMemberAdapter adapter = new MongoMemberAdapter(repository, new MongoMemberIds(operations));

    @Test
    void requiresAnInitializedInt64CounterWithRoomForAnotherId() {
        assertThatThrownBy(() -> adapter.insert("Alice", "alice@example.test", "0123456789"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("member counter");

        ArgumentCaptor<Query> filter = ArgumentCaptor.forClass(Query.class);
        verify(operations).findAndModify(filter.capture(), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(MongoMemberIds.MemberCounter.class));
        assertThat(filter.getValue().getQueryObject()).containsEntry("_id", "member");
        assertThat(filter.getValue().getQueryObject().get("value", Document.class))
                .containsEntry("$type", BsonType.INT64.getValue())
                .containsEntry("$gte", 0L)
                .containsEntry("$lt", Long.MAX_VALUE);
        verify(repository, never()).insert(any(MemberDocument.class));
    }

    @Test
    void propagatesAnIdCollisionInsteadOfOverwritingTheExistingDocument() {
        when(operations.findAndModify(any(Query.class), any(UpdateDefinition.class),
                any(FindAndModifyOptions.class), eq(MongoMemberIds.MemberCounter.class)))
                .thenReturn(new MongoMemberIds.MemberCounter("member", 17L));
        MemberDocument candidate = new MemberDocument(17L, "Alice", "alice@example.test", "0123456789");
        DuplicateKeyException collision = new DuplicateKeyException("Duplicate member ID");
        when(repository.insert(candidate)).thenThrow(collision);

        assertThatThrownBy(() -> adapter.insert(candidate.name(), candidate.email(), candidate.phoneNumber()))
                .isSameAs(collision);
        verify(repository, never()).save(any(MemberDocument.class));
    }
}
