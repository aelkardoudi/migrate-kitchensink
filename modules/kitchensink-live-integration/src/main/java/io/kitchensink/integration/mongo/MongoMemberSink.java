package io.kitchensink.integration.mongo;

import com.mongodb.client.MongoClient;
import io.kitchensink.integration.model.MemberInsert;
import io.kitchensink.mongo.MongoMemberStore;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
@DependsOn("mongoMigrationStartup")
public final class MongoMemberSink implements Consumer<MemberInsert> {
    private final MongoMemberStore members;

    public MongoMemberSink(MongoClient client, MongoConnectionDetails connection) {
        members = new MongoMemberStore(client.getDatabase(connection.getConnectionString().getDatabase())
                .getCollection("members"));
    }

    @Override
    public void accept(MemberInsert event) {
        members.insertIfAbsent(event.id(), event.name(), event.email(), event.phoneNumber());
    }
}
