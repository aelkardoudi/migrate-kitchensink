# Kitchensink JSF frontend

WildFly serves the JSF pages and the public member REST routes. The application uses a Jakarta REST client to forward list, detail and registration requests to Spring Boot. The JSF controller refreshes the request-local member list after a successful registration.

Build from the repository root with `mvn -pl modules/kitchensink -am clean package` to produce `target/kitchensink.war` in this module. Set `KS_SPRING_BASE_URL` to Spring Boot's URL, including the `/kitchensink` context path, when running WildFly separately. See the [Kubernetes guide](../../deploy/README.md) for a local deployment.

Adapted from the Apache 2.0-licensed Red Hat Kitchensink quickstart. Pete Muir authored the original quickstart documentation.
