package io.kitchensink;

import io.kitchensink.adapter.out.h2.MemberEntity;
import io.kitchensink.member.domain.Member;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:member-read-test",
        "spring.datasource.username=sa",
        "spring.sql.init.mode=always"
})
class MemberReadTest {
    @LocalServerPort
    private int port;

    @Autowired
    private Validator validator;

    private RestClient client;

    @BeforeEach
    void connect() {
        client = RestClient.create("http://127.0.0.1:" + port + "/kitchensink/rest/members");
    }

    @Test
    void listsMembersByNameAndKeepsHomonyms() {
        Member[] members = client.get().retrieve().body(Member[].class);

        assertThat(members).extracting(Member::name).containsExactly("Alpha", "Alpha", "Zulu");
        assertThat(members).extracting(Member::id).containsExactlyInAnyOrder(0L, 2L, 3L);
    }

    @Test
    void findsMemberByIdAndPreservesFields() {
        Member member = client.get().uri("/0").retrieve().body(Member.class);

        assertThat(member).isEqualTo(new Member(0L, "Alpha", "alpha@example.test", "0123456789"));
    }

    @Test
    void returnsNotFoundForAbsentAndOutOfRangeIds() {
        assertThatThrownBy(() -> client.get().uri("/42").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
        assertThatThrownBy(() -> client.get().uri("/9223372036854775808").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void memberEntityCarriesLegacyValidationRules() {
        assertThat(validator.validateValue(MemberEntity.class, "name", "Alice")).isEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "name", "Alice 1")).isNotEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "name", "A".repeat(26))).isNotEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "email", "alice@example.test")).isEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "email", "incomplete")).isNotEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "email", "")).isNotEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "phoneNumber", "0123456789")).isEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "phoneNumber", "123456789")).isNotEmpty();
        assertThat(validator.validateValue(MemberEntity.class, "phoneNumber", "012345678X")).isNotEmpty();
    }
}
