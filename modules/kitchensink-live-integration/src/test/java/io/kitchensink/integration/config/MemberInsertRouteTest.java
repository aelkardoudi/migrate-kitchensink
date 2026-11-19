package io.kitchensink.integration.config;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import io.kitchensink.integration.model.MemberInsert;
import org.apache.camel.impl.DefaultCamelContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MemberInsertRouteTest {
    @Test
    void acknowledgesSuccessfulWritesAndReschedulesFailures() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:member-relay-test;DB_CLOSE_DELAY=-1");
        JdbcTemplate h2 = new JdbcTemplate(source);
        h2.execute("CREATE TABLE Member (id BIGINT PRIMARY KEY, name VARCHAR, email VARCHAR, phone_number VARCHAR)");
        h2.execute("""
                CREATE TABLE MEMBER_INSERT_OUTBOX (
                    MEMBER_ID BIGINT PRIMARY KEY,
                    NEXT_ATTEMPT_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        h2.update("INSERT INTO Member VALUES (40, 'Alice', 'alice@example.test', '0123456789'),"
                + " (41, 'Bob', 'bob@example.test', '0234567890')");
        h2.update("INSERT INTO MEMBER_INSERT_OUTBOX (MEMBER_ID) VALUES (40), (41)");

        BlockingQueue<MemberInsert> received = new LinkedBlockingQueue<>();
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.getRegistry().bind("dataSource", source);
            context.addRoutes(new MemberInsertRoute(event -> {
                if (event.id() == 40) {
                    throw new IllegalStateException("Target temporarily unavailable");
                }
                received.add(event);
            }, 25));
            context.start();

            assertEquals(new MemberInsert(41, "Bob", "bob@example.test", "0234567890"),
                    received.poll(5, SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (h2.queryForObject("""
                        SELECT count(*) FROM MEMBER_INSERT_OUTBOX
                        WHERE MEMBER_ID = 41 OR NEXT_ATTEMPT_AT <= CURRENT_TIMESTAMP
                        """, Integer.class) != 0) {
                    Thread.sleep(25);
                }
            });
            assertEquals(40L, h2.queryForObject("SELECT MEMBER_ID FROM MEMBER_INSERT_OUTBOX", Long.class));
        } finally {
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to close the test database", failure);
            }
        }
    }
}
