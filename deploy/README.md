# Local Kubernetes deployment

Use JDK 21, Maven, Docker, kind, kubectl and OpenSSL. Start Docker and create the local cluster with `kind create cluster --name kitchensink` if it does not exist. From the repository root, build and start the applications.

```sh
mvn clean verify
./deploy/demo.sh build
./deploy/demo.sh up
./deploy/demo.sh forward
```

The default kind cluster is `kitchensink`, the namespace is `migrate-kitchensink-final`, and the forwarded HTTP port is `18201`. Use `KS_KIND_CLUSTER` to select a different kind cluster. Use `--namespace` and `--port` to select a namespace and local HTTP port. In IntelliJ IDEA, run **Kitchensink - Kubernetes** and select the namespace under **Services → kind-kitchensink → Workloads**. The **Kitchensink - Pods** and **Kitchensink - Stop** configurations manage the same deployment.

| Command | What it does |
| --- | --- |
| `./deploy/demo.sh build` | Builds the WildFly and Spring images from the Maven artifacts and records their references locally |
| `./deploy/demo.sh up` | Starts MongoDB, then waits for Spring Boot and WildFly |
| `./deploy/demo.sh status` | Shows deployments, services, pods and volumes |
| `./deploy/demo.sh forward` | Forwards the public WildFly service to localhost |
| `./deploy/demo.sh stop` | Stops the three workloads while retaining their resources and database volume |

## Reuse an existing MongoDB deployment

In a namespace that already switched member reads and writes to MongoDB, reuse the same namespace with `./deploy/demo.sh up --namespace YOUR_NAMESPACE`. The rollout reuses the `mongodb-data` claim and existing runtime credentials. It provisions separate Liquibase credentials and starts Spring Boot with them. Spring Boot applies any pending changesets before becoming ready.

For a deployment where H2 is still authoritative, complete the migration using [the cutover stage](../reports/mongodb-cutover/20261119-112528/report.md) and the instructions at tag `migration/07-mongodb-cutover`. Before retiring the transition workloads, verify that H2 writers have stopped, the outbox is empty and all historical members match in MongoDB. Retain the historical volumes.
