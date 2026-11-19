package io.kitchensink.backfill.verify;

import com.mongodb.ReadConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class MemberVerificationTaskletConfiguration {
    private final DataSource source;
    private final MongoCollection<Document> target;

    MemberVerificationTaskletConfiguration(@Qualifier("memberSource") DataSource source, MongoClient mongo,
                                           MongoConnectionDetails connection) {
        this.source = source;
        target = mongo.getDatabase(connection.getConnectionString().getDatabase()).getCollection("members")
                .withReadConcern(ReadConcern.MAJORITY);
    }

    @Bean
    @StepScope
    VerifyMemberCounts verifyCopiedMembers(@Value("#{jobExecutionContext['maxMemberId']}") long maxMemberId) {
        return new VerifyMemberCounts(source, target, maxMemberId);
    }

    @Bean
    VerifyMemberCounts verifyWholeTable() {
        return new VerifyMemberCounts(source, target, null);
    }
}
