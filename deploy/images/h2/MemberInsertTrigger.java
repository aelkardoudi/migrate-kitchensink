package io.kitchensink.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.h2.tools.TriggerAdapter;

public final class MemberInsertTrigger extends TriggerAdapter {
    @Override
    public void fire(Connection connection, ResultSet oldRow, ResultSet newRow) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO PUBLIC.MEMBER_INSERT_OUTBOX (MEMBER_ID) VALUES (?)")) {
            statement.setLong(1, newRow.getLong("ID"));
            statement.executeUpdate();
        }
    }
}
