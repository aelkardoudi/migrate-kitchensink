package io.kitchensink.backfill.config;

import io.kitchensink.backfill.model.SourceMember;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static io.kitchensink.backfill.config.MemberBackfillJobParameters.MAX_MEMBER_ID;

@Configuration(proxyBeanMethods = false)
class MemberBackfillJobConfiguration {
    private final int chunkSize;

    MemberBackfillJobConfiguration(@Value("${kitchensink.backfill.chunk-size}") int chunkSize) {
        this.chunkSize = chunkSize;
    }

    @Bean
    Job memberBackfill(JobRepository repository, @Qualifier("captureUpperBound") Step captureUpperBound,
                       @Qualifier("copyMembers") Step copyMembers,
                       @Qualifier("verifyBackfillMembers") Step verifyBackfillMembers,
                       @Qualifier("verifyAllMembers") Step verifyAllMembers) {
        return new JobBuilder("memberBackfill", repository)
                .validator(new MemberBackfillJobParameters())
                .start(captureUpperBound)
                .next(copyMembers)
                .next(verifyBackfillMembers)
                .next(verifyAllMembers)
                .build();
    }

    @Bean
    Step captureUpperBound(JobRepository repository, PlatformTransactionManager transactionManager,
                           CaptureMemberUpperBound tasklet) throws Exception {
        var promotion = new ExecutionContextPromotionListener();
        promotion.setKeys(new String[]{MAX_MEMBER_ID});
        promotion.afterPropertiesSet();
        return new StepBuilder("captureUpperBound", repository)
                .tasklet(tasklet, transactionManager)
                .listener(promotion)
                .build();
    }

    @Bean
    Step copyMembers(JobRepository repository, PlatformTransactionManager transactionManager,
                     JdbcPagingItemReader<SourceMember> sourceMemberReader,
                     @Qualifier("mongoMemberWriter") ItemWriter<SourceMember> writer) {
        return new StepBuilder("copyMembers", repository)
                .<SourceMember, SourceMember>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(sourceMemberReader)
                .writer(writer)
                .build();
    }

    @Bean
    Step verifyBackfillMembers(JobRepository repository, PlatformTransactionManager transactionManager,
                               @Qualifier("verifyCopiedMembers") Tasklet tasklet) {
        return new StepBuilder("verifyBackfillMembers", repository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    Step verifyAllMembers(JobRepository repository, PlatformTransactionManager transactionManager,
                          @Qualifier("verifyWholeTable") Tasklet tasklet) {
        return new StepBuilder("verifyAllMembers", repository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<SourceMember> sourceMemberReader(
            @Qualifier("memberSource") DataSource memberSource,
            @Value("#{jobExecutionContext['maxMemberId']}") long maxMemberId) throws Exception {
        return new JdbcPagingItemReaderBuilder<SourceMember>()
                .name("sourceMemberReader")
                .dataSource(memberSource)
                .selectClause("ID, NAME, EMAIL, PHONE_NUMBER")
                .fromClause("PUBLIC.MEMBER")
                .whereClause("ID <= :maxMemberId")
                .parameterValues(Map.of(MAX_MEMBER_ID, maxMemberId))
                .sortKeys(Map.of("ID", Order.ASCENDING))
                .pageSize(chunkSize)
                .rowMapper((row, index) -> new SourceMember(row.getLong("ID"), row.getString("NAME"),
                        row.getString("EMAIL"), row.getString("PHONE_NUMBER")))
                .build();
    }
}
