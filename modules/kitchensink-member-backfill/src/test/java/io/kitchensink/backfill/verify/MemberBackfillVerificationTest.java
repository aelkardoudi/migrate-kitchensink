package io.kitchensink.backfill.verify;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberBackfillVerificationTest {
    @Test
    void comparesExactTotalsAndCountsRowsWithNullableFields() throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:member-count-verification");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE MEMBER (ID BIGINT PRIMARY KEY, EMAIL VARCHAR)");
            assertEquals(0L, MemberCountVerification.countSource(connection, null));
            statement.execute("INSERT INTO MEMBER VALUES (0, NULL), (12, 'test@example.test')");

            long h2 = MemberCountVerification.countSource(connection, null);
            assertEquals(2L, h2);
            assertTrue(new MemberCountVerification.Counts(h2, 2, 0, true).match());
            assertFalse(new MemberCountVerification.Counts(h2, 1, 0, true).match());
        }
    }
}
