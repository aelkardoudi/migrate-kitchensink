package io.kitchensink.adapter.out.mongo;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

@Component("mongoHealthIndicator")
class MongoMemberHealthIndicator extends AbstractHealthIndicator {
    private final MongoMemberIds ids;

    MongoMemberHealthIndicator(MongoMemberIds ids) {
        super("MongoDB member counter check failed");
        this.ids = ids;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        if (ids.hasAvailableId()) {
            builder.up();
        } else {
            builder.down().withDetail("memberCounter", "missing, invalid or exhausted");
        }
    }
}
