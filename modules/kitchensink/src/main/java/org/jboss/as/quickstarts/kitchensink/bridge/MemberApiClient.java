package org.jboss.as.quickstarts.kitchensink.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.as.quickstarts.kitchensink.model.Member;

@ApplicationScoped
public class MemberApiClient {

    private static final Logger LOG = Logger.getLogger(MemberApiClient.class.getName());
    private static final String DEFAULT_BASE_URL = "http://modern:8080/kitchensink";
    private static final GenericType<List<Member>> MEMBER_LIST = new GenericType<List<Member>>() {
    };
    private static final GenericType<Map<String, String>> FIELD_ERRORS = new GenericType<Map<String, String>>() {
    };

    private Client client;
    private WebTarget members;

    @PostConstruct
    void init() {
        String baseUrl = System.getenv("KS_SPRING_BASE_URL");
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

    public void register(Member member) {
        Map<String, String> request = new HashMap<>();
        request.put("name", member.getName());
        request.put("email", member.getEmail());
        request.put("phoneNumber", member.getPhoneNumber());

        try (Response response = members.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.json(request))) {
            Response.Status status = Response.Status.fromStatusCode(response.getStatus());
            if (status == Response.Status.BAD_REQUEST || status == Response.Status.CONFLICT) {
                Map<String, String> errors = response.readEntity(FIELD_ERRORS);
                if (isRegistrationRejection(status, errors)) {
                    throw new MemberRegistrationException(status, errors);
                }
            }
            requireOk(response);
        } catch (ProcessingException failure) {
            throw unavailable(failure);
        }
    }

    private boolean isRegistrationRejection(Response.Status status, Map<String, String> errors) {
        if (errors == null || errors.isEmpty() || errors.containsValue(null)) {
            return false;
        }
        if (status == Response.Status.CONFLICT) {
            return errors.size() == 1 && errors.containsKey("email");
        }
        return errors.keySet().stream().allMatch(field -> "name".equals(field)
                || "email".equals(field) || "phoneNumber".equals(field));
    }

    private void requireOk(Response response) {
        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            LOG.warning("Spring member API returned HTTP " + response.getStatus());
            throw new ServiceUnavailableException("Spring member API unavailable");
        }
    }

    private ServiceUnavailableException unavailable(ProcessingException failure) {
        LOG.log(Level.WARNING, "Spring member API request failed", failure);
        return new ServiceUnavailableException("Spring member API unavailable");
    }
}
