package org.jboss.as.quickstarts.kitchensink.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.core.Response;

public class MemberRegistrationException extends RuntimeException {

    private final Response.Status status;
    private final Map<String, String> errors;

    public MemberRegistrationException(Response.Status status, Map<String, String> errors) {
        super(String.join(" ", errors.values()));
        this.status = status;
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public Response.Status getStatus() {
        return status;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
