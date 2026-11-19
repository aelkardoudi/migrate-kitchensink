package io.kitchensink.adapter.out.mongo;

import lombok.RequiredArgsConstructor;
import org.bson.BsonType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MongoMemberIds {
    private final MongoOperations operations;

    long nextId() {
        MemberCounter counter = operations.findAndModify(availableCounter(), new Update().inc("value", 1L),
                FindAndModifyOptions.options().returnNew(true), MemberCounter.class);
        if (counter == null) {
            throw new IllegalStateException("MongoDB member counter is missing, invalid or exhausted");
        }
        return counter.value();
    }

    boolean hasAvailableId() {
        return operations.exists(availableCounter(), MemberCounter.class);
    }

    private static Query availableCounter() {
        return Query.query(Criteria.where("_id").is("member").and("value")
                .type(BsonType.INT64.getValue()).gte(0L).lt(Long.MAX_VALUE));
    }

    @Document("counters")
    record MemberCounter(@Id String id, long value) {
    }
}
