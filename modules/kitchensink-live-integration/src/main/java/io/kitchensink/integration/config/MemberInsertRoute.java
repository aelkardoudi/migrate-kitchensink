package io.kitchensink.integration.config;

import java.util.Map;
import java.util.function.Consumer;

import io.kitchensink.integration.model.MemberInsert;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class MemberInsertRoute extends RouteBuilder {
    private static final String PENDING_INSERTS = """
            SELECT m.ID AS MEMBER_ID, m.NAME, m.EMAIL, m.PHONE_NUMBER
            FROM MEMBER_INSERT_OUTBOX o JOIN Member m ON m.ID = o.MEMBER_ID
            WHERE o.NEXT_ATTEMPT_AT <= CURRENT_TIMESTAMP
            ORDER BY o.NEXT_ATTEMPT_AT, o.MEMBER_ID
            FETCH FIRST 100 ROWS ONLY
            """;
    private static final String ACKNOWLEDGE = "DELETE FROM MEMBER_INSERT_OUTBOX WHERE MEMBER_ID = :#MEMBER_ID";
    private static final String RETRY_LATER = """
            UPDATE MEMBER_INSERT_OUTBOX SET NEXT_ATTEMPT_AT = DATEADD(SECOND, 30, CURRENT_TIMESTAMP)
            WHERE MEMBER_ID = :#MEMBER_ID
            """;

    private final Consumer<MemberInsert> sink;
    private final int pollDelayMillis;

    MemberInsertRoute(Consumer<MemberInsert> sink,
                      @Value("${kitchensink.relay.poll-interval-ms}") int pollDelayMillis) {
        if (pollDelayMillis <= 0) {
            throw new IllegalArgumentException("kitchensink.relay.poll-interval-ms must be greater than zero");
        }
        this.sink = sink;
        this.pollDelayMillis = pollDelayMillis;
    }

    @Override
    public void configure() {
        from("sql:" + PENDING_INSERTS.strip() + "?dataSource=#dataSource&initialDelay=0&delay=" + pollDelayMillis
                + "&onConsume=" + ACKNOWLEDGE + "&onConsumeFailed=" + RETRY_LATER.strip())
                .routeId("relay-member-inserts")
                .process(exchange -> {
                    Map<?, ?> row = exchange.getMessage().getBody(Map.class);
                    sink.accept(new MemberInsert(((Number) row.get("MEMBER_ID")).longValue(),
                            (String) row.get("NAME"), (String) row.get("EMAIL"), (String) row.get("PHONE_NUMBER")));
                });
    }
}
