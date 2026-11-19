# Deploy Kitchensink on kind

In IntelliJ IDEA, select **Kitchensink - Kubernetes** in the top run selector and click **Run**. It starts WildFly, H2 and Spring Boot on the `kind-kitchensink` cluster, then serves the application at <http://127.0.0.1:18201/kitchensink/>. Click **Stop** once to stop the stack while keeping the database volume and credentials. Allow shutdown to finish. Build the images before starting the run configuration.

**Kitchensink - Stop** stops WildFly and H2, including when they started outside the IDE. Use `./deploy/modern.sh stop` to stop Spring Boot separately. **Kitchensink - Pods** prints their status. For the live pod tree and logs, open **View → Tool Windows → Services**, choose the `migrate-kitchensink-dev` namespace under `kind-kitchensink`, and expand **Workloads → Pods**.

WildFly 28.0.1.Final serves the WAR built from `modules/kitchensink/`. H2 2.1.214 runs in a StatefulSet and stores its data in the `h2-data` volume. The WildFly image configures the WAR to connect to H2 over TCP and validate the schema that H2 creates.

You need JDK 21, Maven, Docker with BuildKit, kind, kubectl, and OpenSSL.

1. From the repository root, build the WAR, the Spring Boot JAR and their images.

   ```sh
   mvn -Pmodern clean package
   ./deploy/build-images.sh
   ./deploy/modern.sh build
   ```

2. Use the `kitchensink` kind cluster. If it doesn't exist, create it with `kind create cluster --name kitchensink`. Start H2 and WildFly, then start Spring Boot in the same `migrate-kitchensink-dev` namespace.

   ```sh
   ./deploy/demo.sh up
   ./deploy/modern.sh up
   ```

3. Forward a local port to WildFly.

   ```sh
   ./deploy/demo.sh forward
   ```

   Open the [Kitchensink UI](http://127.0.0.1:18201/kitchensink/) or the [member list](http://127.0.0.1:18201/kitchensink/rest/members). Keep the port-forward command running while you use the application.

`./deploy/demo.sh status` lists the Kubernetes resources. Stop Spring Boot with `./deploy/modern.sh stop`, then use `./deploy/demo.sh stop` to stop WildFly and H2 while keeping the data and credentials. Pass the same `--namespace` to both scripts for a separate environment. Use `forward --port 18202` to choose a different local port. Set `KS_KIND_CLUSTER` to use a different local kind cluster.

The build verifies the WildFly distribution's SHA-256 checksum. To reuse a local archive, set `KS_WILDFLY_ZIP` to its path before running `build-images.sh`. The script writes the local image references to `.run/deploy/images.tsv`. `demo.sh up` loads those images into kind and inserts their references into the manifest before applying it.

## Deploy the Spring member API

Use JDK 21 to build the Spring Boot JAR. Deploy its image in the kind namespace that already contains the H2 StatefulSet and Service. Spring connects directly to H2 and has its own Deployment and Service.

```sh
mvn -Pmodern -pl .,modules/new-kitchensink-boot -am clean package
./deploy/modern.sh build
./deploy/modern.sh up
./deploy/modern.sh forward
```

Open the [Spring member list](http://127.0.0.1:18203/kitchensink/rest/members) or [member 0](http://127.0.0.1:18203/kitchensink/rest/members/0). Kubernetes uses `modern:8080` for the internal service. Spring's `members_writer` account has `SELECT` and `INSERT` on the existing `Member` table. The script creates `h2-write-credentials` and reuses it across restarts. Pass `--namespace` to select the namespace where H2 runs.

`./deploy/modern.sh status` shows the Spring service and pods. `./deploy/modern.sh stop` scales Spring to zero. Keep H2 running while Spring is serving requests.

## Deploy MongoDB and the Camel relay

Use the test namespace on kind and a Docker version that supports `docker image save --platform`. Build the three application images and the H2 image with the insert trigger.

```sh
mvn -P modern,live-integration clean package
./deploy/build-images.sh
./deploy/modern.sh build
./deploy/live-integration.sh build
```

Start the existing stack before adding MongoDB and the relay.

```sh
./deploy/demo.sh up --namespace migrate-kitchensink-test
./deploy/modern.sh up --namespace migrate-kitchensink-test
./deploy/live-integration.sh up --namespace migrate-kitchensink-test
```

`./deploy/live-integration.sh status` shows both workloads and the MongoDB volume. `./deploy/live-integration.sh logs` follows Camel logs. In IntelliJ IDEA, select the `migrate-kitchensink-test` namespace under **Services → kind-kitchensink** and expand **Workloads → Pods**. MongoDB uses the internal `mongodb:27017` service and the persistent `mongodb-data` claim. The relay applies the MongoDB changelog before it reads the H2 outbox.

`./deploy/live-integration.sh stop` scales down the relay and MongoDB and preserves the volume and credentials. Keep the H2 image with the insert trigger while the relay is stopped so new registrations stay queued for restart.

## Run the Spring Batch member backfill

Start H2, MongoDB and the Camel relay from this revision in the same namespace before running the batch. Follow the [live integration deployment](#deploy-mongodb-and-the-camel-relay) to update the relay in an existing namespace. From the repository root, build the job with JDK 21 and launch it once.

```sh
mvn -Pbatch -pl modules/kitchensink-member-backfill -am clean package
./deploy/member-backfill.sh build
./deploy/member-backfill.sh run --run-id history-01 --namespace migrate-kitchensink-test
```

The last command prints the Kubernetes Job name and the command to follow its logs. `./deploy/member-backfill.sh status` lists jobs, pods and the `member-backfill-data` claim in that namespace. The batch connects to H2 with `SELECT` permission on `PUBLIC.MEMBER` and `PUBLIC.MEMBER_INSERT_OUTBOX`, and it uses the MongoDB account provisioned by the live integration. Keep the relay active to cover concurrent registrations. By default, Spring Batch records the highest visible H2 member ID for that run and reads up to that ID. Members already copied by Camel are safe to encounter again.

Pass `--max-member-id` to set a known inclusive upper bound for an intentional partial run.

```sh
./deploy/member-backfill.sh run --run-id history-through-500 --max-member-id 500 --namespace migrate-kitchensink-test
```

For a failed execution reported by Spring Batch, repeat the same command with the same `--run-id` and the same `--max-member-id` value or omission. Each attempt creates a Kubernetes Job and reuses the checkpoint in `member-backfill-data`. If a pod was interrupted before Spring Batch could report failure, wait for its Kubernetes Job to finish and use a fresh run ID to rescan safely. Use a fresh ID for an intentional new scan after a completed run.

The job runs `captureUpperBound`, then `copyMembers`, then `verifyBackfillMembers` and `verifyAllMembers`. After the copy commits, `verifyBackfillMembers` compares counts up to and including the saved `maxMemberId`. `verifyAllMembers` compares the full H2 and MongoDB totals. Each verification step accepts a stable gap only when it equals the number of pending outbox IDs in scope that are still absent from MongoDB. An unexplained gap that persists across retries fails the job.

A partial copy can pass `verifyBackfillMembers` and still fail `verifyAllMembers` if historical rows above the bound remain missing from MongoDB.

### Run an independent count check

Use the standalone verification Job for an on-demand comparison of the exact totals for all H2 member rows and MongoDB member documents.

```sh
./deploy/member-backfill.sh verify --namespace migrate-kitchensink-test
```

Follow the log command printed for the `member-backfill-verify-` Job to see the exact row count in H2 and document count in MongoDB. The Job retries briefly if the totals differ. It exits with `0` when the totals match, `1` when they still differ, or `2` on a configuration or database read error.

## Roll back delegated registration

WildFly sends member reads and registrations from the JSF form and REST API to Spring Boot. If Spring is unavailable, WildFly returns HTTP 503 for REST requests. For standalone WildFly, set `KS_SPRING_BASE_URL` to the Spring URL including the `/kitchensink` context path. The previous `KS_SPRING_READ_BASE_URL` setting still works when `KS_SPRING_BASE_URL` is unset.

For an existing stack, deploy the new Spring image before the new WildFly image. Before updating WildFly, record the image reference from `migration/03-delegate-reading`, which still writes directly to H2.

```sh
kubectl --context kind-kitchensink -n migrate-kitchensink-dev get deployment legacy \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="legacy")].image}{"\n"}'
```

To roll back, set `PREVIOUS_WILDFLY_IMAGE` to that reference. Ensure the image is loaded in kind, then redeploy it.

```sh
kubectl --context kind-kitchensink -n migrate-kitchensink-dev set image deployment/legacy \
  legacy="$PREVIOUS_WILDFLY_IMAGE"
kubectl --context kind-kitchensink -n migrate-kitchensink-dev rollout status deployment/legacy
```

`kubectl rollout undo deployment/legacy` is also suitable if the previous revision contains that image. It writes to the same H2 service, volume and `Member_SEQ` sequence. Keep Spring running to serve the reads. If you run `demo.sh up` again, it applies the image recorded in `.run/deploy/images.tsv`.
