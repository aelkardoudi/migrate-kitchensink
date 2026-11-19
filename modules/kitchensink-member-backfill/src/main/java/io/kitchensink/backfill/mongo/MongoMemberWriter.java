package io.kitchensink.backfill.mongo;

import com.mongodb.client.MongoClient;
import io.kitchensink.backfill.model.SourceMember;
import io.kitchensink.mongo.MongoMemberStore;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.stereotype.Component;

@Component
class MongoMemberWriter implements ItemWriter<SourceMember> {
    private final MongoMemberStore members;

    MongoMemberWriter(MongoClient client, MongoConnectionDetails connection) {
        members = new MongoMemberStore(client.getDatabase(connection.getConnectionString().getDatabase())
                .getCollection("members"));
    }

    @Override
    public void write(Chunk<? extends SourceMember> chunk) {
        for (SourceMember member : chunk) {
            members.insertIfAbsent(member.id(), member.name(), member.email(), member.phoneNumber());
        }
    }
}
