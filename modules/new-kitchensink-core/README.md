# new-kitchensink-core

The Java 21 core defines the member use cases and the interfaces their adapters implement.

- `QueryMembers` exposes the list and find-by-ID operations.
- `RegisterMember` exposes registration.
- `MemberService` implements the use cases and delegates data access to `MemberRepository`. Lombok generates its constructor.
- `Member` is an immutable record with `id`, `name`, `email`, and `phoneNumber`.

From the repository root, build this module with JDK 21 and Maven.

```sh
mvn -f modules/new-kitchensink-core/pom.xml package
```

The [Boot module](../new-kitchensink-boot/README.md) provides the HTTP and MongoDB adapters.
