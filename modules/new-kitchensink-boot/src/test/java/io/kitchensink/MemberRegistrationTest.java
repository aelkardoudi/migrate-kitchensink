package io.kitchensink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.kitchensink.member.domain.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:member-registration-test",
        "spring.datasource.username=sa",
        "spring.sql.init.mode=always"
})
class MemberRegistrationTest {
    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void connect() {
        client = RestClient.create("http://127.0.0.1:" + port + "/kitchensink/rest/members");
    }

    @Test
    void registersAlongsideExistingMembersAndPreservesPhoneNumber() {
        ResponseEntity<String> response = register("""
                {"id":0,"name":"Élodie أمينة","email":"elodie@example.test","phoneNumber":"0123456789"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
        assertThat(client.get().uri("/0").retrieve().body(Member.class))
                .isEqualTo(new Member(0L, "Alpha", "alpha@example.test", "0123456789"));
        assertThat(client.get().retrieve().body(Member[].class))
                .filteredOn(member -> member.email().equals("elodie@example.test"))
                .singleElement().satisfies(member -> {
                    assertThat(member.id()).isGreaterThan(3L);
                    assertThat(member.name()).isEqualTo("Élodie أمينة");
                    assertThat(member.phoneNumber()).isEqualTo("0123456789");
                });
    }

    @Test
    void reportsValidationErrorsForMissingAndInvalidFields() {
        ResponseEntity<Map<String, String>> response = registrationRequest("""
                {"name":"Bad1","email":null,"phoneNumber":"01234ABCDE"}
                """).toEntity(new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsOnlyKeys("name", "email", "phoneNumber")
                .containsEntry("name", "Must not contain numbers")
                .hasEntrySatisfying("email", message ->
                        assertThat(message).isIn("must not be null", "must not be empty"));
    }

    @Test
    void rejectsAnEmailAlreadyPresentInTheLegacyDatabase() {
        ResponseEntity<Map<String, String>> response = registrationRequest("""
                {"name":"Appropriate Name","email":"alpha@example.test","phoneNumber":"0123456789"}
                """).toEntity(new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsExactlyEntriesOf(Map.of("email", "Email taken"));
    }

    @Test
    void commitsOnlyOneConcurrentRegistrationForAnEmail() {
        String request = """
                {"name":"Concurrent Member","email":"concurrent@example.test","phoneNumber":"0123456789"}
                """;
        CompletableFuture<ResponseEntity<String>> first = CompletableFuture.supplyAsync(() -> register(request));
        CompletableFuture<ResponseEntity<String>> second = CompletableFuture.supplyAsync(() -> register(request));

        assertThat(new int[] {first.join().getStatusCode().value(), second.join().getStatusCode().value()})
                .containsExactlyInAnyOrder(200, 409);
        assertThat(client.get().retrieve().body(Member[].class))
                .filteredOn(member -> member.email().equals("concurrent@example.test"))
                .hasSize(1);
    }

    private ResponseEntity<String> register(String request) {
        return registrationRequest(request).toEntity(String.class);
    }

    private RestClient.ResponseSpec registrationRequest(String request) {
        return client.post().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en").body(request).retrieve()
                .onStatus(status -> status.isError(), (sent, received) -> {});
    }
}
