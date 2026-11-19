package io.kitchensink.backfill.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import static io.kitchensink.backfill.config.MemberBackfillJobParameters.MAX_MEMBER_ID;

@Component
class CaptureMemberUpperBound implements Tasklet {
    private static final Logger LOG = LoggerFactory.getLogger(CaptureMemberUpperBound.class);

    private final JdbcTemplate source;

    CaptureMemberUpperBound(@Qualifier("memberSource") DataSource memberSource) {
        source = new JdbcTemplate(memberSource);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext)
            throws InvalidJobParametersException {
        var execution = contribution.getStepExecution();
        Long configuredId = MemberBackfillJobParameters.configuredMaxMemberId(execution.getJobParameters());
        Long maxId = configuredId == null ? source.queryForObject("SELECT MAX(ID) FROM PUBLIC.MEMBER", Long.class) : configuredId;
        execution.getExecutionContext().putLong(MAX_MEMBER_ID, maxId == null ? Long.MIN_VALUE : maxId);
        LOG.info("Backfill upper member ID: {} ({})", maxId == null ? "empty source" : maxId,
                configuredId == null ? "H2" : "job parameter");
        return RepeatStatus.FINISHED;
    }
}
