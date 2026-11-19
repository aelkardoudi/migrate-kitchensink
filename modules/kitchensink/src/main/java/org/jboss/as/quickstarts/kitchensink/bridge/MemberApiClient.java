package org.jboss.as.quickstarts.kitchensink.bridge;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.as.quickstarts.kitchensink.model.Member;

@ApplicationScoped
public class MemberApiClient {

    private static final String DEFAULT_BASE_URL = "http://modern:8080/kitchensink";
    private static final GenericType<List<Member>> MEMBER_LIST = new GenericType<List<Member>>() {
    };

    @Inject
    private Logger log;

    private Client client;
    private WebTarget members;

    @PostConstruct
    void init() {
        String baseUrl = System.getenv("KS_SPRING_READ_BASE_URL");
        client = ClientBuilder.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        members = client.target(baseUrl == null ? DEFAULT_BASE_URL : baseUrl).path("rest/members");
    }

    @PreDestroy
    void close() {
        client.close();
    }

    public List<Member> findAllOrderedByName() {
        try (Response response = members.request(MediaType.APPLICATION_JSON_TYPE).get()) {
            requireOk(response);
            return response.readEntity(MEMBER_LIST);
        } catch (ProcessingException failure) {
            throw unavailable(failure);
        }
    }

    public Member findById(Long id) {
        try (Response response = members.path(id.toString()).request(MediaType.APPLICATION_JSON_TYPE).get()) {
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                return null;
            }
            requireOk(response);
            return response.readEntity(Member.class);
        } catch (ProcessingException failure) {
            throw unavailable(failure);
        }
    }

    private void requireOk(Response response) {
        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            log.warning("Spring member API returned HTTP " + response.getStatus());
            throw new ServiceUnavailableException("Spring member API unavailable");
        }
    }

    private ServiceUnavailableException unavailable(ProcessingException failure) {
        log.log(Level.WARNING, "Spring member API request failed", failure);
        return new ServiceUnavailableException("Spring member API unavailable");
    }
}
