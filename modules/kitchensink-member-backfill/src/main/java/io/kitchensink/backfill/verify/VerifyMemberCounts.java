package io.kitchensink.backfill.verify;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import javax.sql.DataSource;

class VerifyMemberCounts implements Tasklet {
    private static final Logger LOG = LoggerFactory.getLogger(VerifyMemberCounts.class);

    private final DataSource source;
    private final MongoCollection<Document> target;
    private final Long maxMemberId;

    VerifyMemberCounts(DataSource source, MongoCollection<Document> target, Long maxMemberId) {
        this.source = source;
        this.target = target;
        this.maxMemberId = maxMemberId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        try (var connection = source.getConnection()) {
            var counts = MemberCountVerification.verify(connection, target, maxMemberId);
            String scope = maxMemberId == null ? "scope=all" : "scope=backfill maxMemberId=" + maxMemberId;
            String summary = "%s h2=%d mongo=%d pendingMissing=%d stable=%s"
                    .formatted(scope, counts.h2(), counts.mongo(), counts.pendingMissing(), counts.stable());
            if (counts.match()) {
                LOG.info("Member verification COUNT_MATCH {}", summary);
            } else if (counts.explainedByOutbox()) {
                LOG.info("Member verification COUNT_GAP_EXPLAINED_BY_OUTBOX {}", summary);
            } else {
                throw new IllegalStateException("Member verification COUNT_MISMATCH " + summary);
            }
        }
        return RepeatStatus.FINISHED;
    }
}
