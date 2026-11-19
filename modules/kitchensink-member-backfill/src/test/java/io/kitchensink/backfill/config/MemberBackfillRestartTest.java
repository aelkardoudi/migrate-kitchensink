package io.kitchensink.backfill.config;

import io.kitchensink.backfill.model.SourceMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.JobExecutionExitCodeGenerator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MemberBackfillRestartTest {
    private static final Tasklet NO_OP_VERIFICATION = (contribution, chunk) -> RepeatStatus.FINISHED;

    @TempDir
    Path databases;

    @Test
    void resumesFromCommittedPageWithoutChangingTheInitialUpperBound() {
        var source = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl("members"), "sa", ""));
        source.execute("CREATE TABLE MEMBER (ID BIGINT PRIMARY KEY, NAME VARCHAR, EMAIL VARCHAR, PHONE_NUMBER VARCHAR)");
        addMember(source, 0);
        addMember(source, 12);
        addMember(source, 48);
        addMember(source, 85);

        var destination = new LinkedHashMap<Long, SourceMember>();
        try (var first = launch(chunk -> {
            for (SourceMember member : chunk) {
                destination.putIfAbsent(member.id(), member);
                if (member.id() == 48) {
                    throw new IllegalStateException("Simulated failure after the remote write");
                }
            }
        }, "run.id=restart-test")) {
            assertNotEquals(0, first.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
            assertExecution(first, BatchStatus.FAILED);
            assertEquals(List.of(0L, 12L, 48L), List.copyOf(destination.keySet()));
        }

        addMember(source, 101);
        var resumed = new ArrayList<Long>();
        try (var second = launch(chunk -> {
            for (SourceMember member : chunk) {
                resumed.add(member.id());
                destination.putIfAbsent(member.id(), member);
            }
        }, "run.id=restart-test")) {
            assertEquals(0, second.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
            assertExecution(second, BatchStatus.COMPLETED);
        }

        assertEquals(List.of(48L, 85L), resumed);
        assertEquals(List.of(0L, 12L, 48L, 85L), List.copyOf(destination.keySet()));
        assertEquals("0123456789", destination.get(0L).phoneNumber());
    }

    @Test
    void usesAnExplicitInclusiveUpperBoundOfZero() {
        var source = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl("members"), "sa", ""));
        source.execute("CREATE TABLE MEMBER (ID BIGINT PRIMARY KEY, NAME VARCHAR, EMAIL VARCHAR, PHONE_NUMBER VARCHAR)");
        addMember(source, 0);
        addMember(source, 12);

        var copied = new ArrayList<Long>();
        try (var context = launch(chunk -> chunk.forEach(member -> copied.add(member.id())),
                "run.id=explicit-bound-test", "maxMemberId=0")) {
            assertEquals(0, context.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
            assertEquals(List.of(0L), copied);
        }
    }

    @Test
    void resumesFailedVerificationWithoutRecopyingMembers() {
        var source = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl("members"), "sa", ""));
        source.execute("CREATE TABLE MEMBER (ID BIGINT PRIMARY KEY, NAME VARCHAR, EMAIL VARCHAR, PHONE_NUMBER VARCHAR)");
        addMember(source, 0);
        var copied = new ArrayList<Long>();
        ItemWriter<SourceMember> writer = chunk -> chunk.forEach(member -> copied.add(member.id()));

        try (var first = launch(writer, (contribution, chunk) -> {
            assertEquals(List.of(0L), copied);
            throw new IllegalStateException("Simulated count discrepancy");
        }, "run.id=verify-restart-test")) {
            assertNotEquals(0, first.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
            var parameters = new JobParametersBuilder().addString("run.id", "verify-restart-test").toJobParameters();
            var execution = first.getBean(JobRepository.class).getLastJobExecution("memberBackfill", parameters);
            assertEquals(BatchStatus.FAILED, execution.getStatus());
            assertEquals(BatchStatus.COMPLETED, execution.getStepExecutions().stream()
                    .filter(step -> step.getStepName().equals("copyMembers")).findFirst().orElseThrow().getStatus());
            assertEquals(BatchStatus.FAILED, execution.getStepExecutions().stream()
                    .filter(step -> step.getStepName().equals("verifyBackfillMembers")).findFirst().orElseThrow().getStatus());
        }

        try (var second = launch(writer, "run.id=verify-restart-test")) {
            assertEquals(0, second.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
        }
        assertEquals(List.of(0L), copied);
    }

    @Test
    void runsGlobalVerificationAfterBackfillVerificationAndResumesOnlyTheFailedStep() {
        var source = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl("members"), "sa", ""));
        source.execute("CREATE TABLE MEMBER (ID BIGINT PRIMARY KEY, NAME VARCHAR, EMAIL VARCHAR, PHONE_NUMBER VARCHAR)");
        addMember(source, 0);

        var actions = new ArrayList<String>();
        ItemWriter<SourceMember> writer = chunk -> actions.add("copy");
        Tasklet backfillVerification = (contribution, chunk) -> {
            actions.add("verifyBackfill");
            return RepeatStatus.FINISHED;
        };

        try (var first = launch(writer, backfillVerification, (contribution, chunk) -> {
            actions.add("verifyAll");
            throw new IllegalStateException("Simulated whole-table discrepancy");
        }, "run.id=verify-all-restart-test")) {
            assertNotEquals(0, first.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
        }
        assertEquals(List.of("copy", "verifyBackfill", "verifyAll"), actions);

        try (var second = launch(writer, backfillVerification, (contribution, chunk) -> {
            actions.add("verifyAll");
            return RepeatStatus.FINISHED;
        }, "run.id=verify-all-restart-test")) {
            assertEquals(0, second.getBean(JobExecutionExitCodeGenerator.class).getExitCode());
        }
        assertEquals(List.of("copy", "verifyBackfill", "verifyAll", "verifyAll"), actions);
    }

    private ConfigurableApplicationContext launch(ItemWriter<SourceMember> writer, String... args) {
        return launch(writer, NO_OP_VERIFICATION, args);
    }

    private ConfigurableApplicationContext launch(ItemWriter<SourceMember> writer, Tasklet verifier, String... args) {
        return launch(writer, verifier, NO_OP_VERIFICATION, args);
    }

    private ConfigurableApplicationContext launch(ItemWriter<SourceMember> writer, Tasklet backfillVerification,
                                                  Tasklet globalVerification, String... args) {
        return new SpringApplicationBuilder(BatchTestConfiguration.class)
                .properties(Map.of(
                        "KS_H2_JDBC_URL", jdbcUrl("members"),
                        "KS_H2_USER", "sa",
                        "KS_H2_PASSWORD", "",
                        "KS_BATCH_JDBC_URL", jdbcUrl("metadata"),
                        "KS_BACKFILL_CHUNK_SIZE", "2",
                        "spring.main.banner-mode", "off"))
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("mongoMemberWriter", writer);
                    context.getBeanFactory().registerSingleton("verifyCopiedMembers", backfillVerification);
                    context.getBeanFactory().registerSingleton("verifyWholeTable", globalVerification);
                })
                .run(args);
    }

    private void assertExecution(ConfigurableApplicationContext context, BatchStatus status) {
        var parameters = new JobParametersBuilder().addString("run.id", "restart-test").toJobParameters();
        var execution = context.getBean(JobRepository.class).getLastJobExecution("memberBackfill", parameters);
        assertEquals(status, execution.getStatus());
        assertEquals(85L, execution.getExecutionContext().getLong(MemberBackfillJobParameters.MAX_MEMBER_ID));
    }

    private String jdbcUrl(String database) {
        return "jdbc:h2:file:" + databases.resolve(database);
    }

    private void addMember(JdbcTemplate source, long id) {
        source.update("INSERT INTO MEMBER VALUES (?, ?, ?, ?)", id, "Batch Test", id + "@example.test", "0123456789");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = MongoAutoConfiguration.class)
    @Import({SourceDataSourceConfiguration.class, CaptureMemberUpperBound.class, MemberBackfillJobConfiguration.class})
    static class BatchTestConfiguration {
    }
}
