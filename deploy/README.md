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

## Deploy the Spring member reads

Use JDK 21 to build the Spring Boot JAR. Deploy its image in the kind namespace that already contains the H2 StatefulSet and Service. Spring connects directly to H2 and has its own Deployment and Service.

```sh
mvn -Pmodern -pl .,modules/new-kitchensink-boot -am clean package
./deploy/modern.sh build
./deploy/modern.sh up
./deploy/modern.sh forward
```

Open the [Spring member list](http://127.0.0.1:18203/kitchensink/rest/members) or [member 0](http://127.0.0.1:18203/kitchensink/rest/members/0). Kubernetes uses `modern:8080` for the internal service. Spring's H2 account has `SELECT` access on the existing `Member` table. The script creates `h2-read-credentials` and reuses it across restarts. Pass `--namespace` to select the namespace where H2 runs.

`./deploy/modern.sh status` shows the Spring service and pods. `./deploy/modern.sh stop` scales Spring to zero. Keep H2 running while Spring is serving requests.

## Roll back delegated reads

The current WildFly image sends member list and detail requests to Spring Boot. WildFly handles registrations with the shared H2 database. If Spring is unavailable, the WildFly REST reads return HTTP 503. For standalone WildFly, set `KS_SPRING_READ_BASE_URL` to the Spring URL including the `/kitchensink` context path.

Before deploying the new WildFly image, record the image reference of the version that reads H2 directly.

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

`kubectl rollout undo deployment/legacy` is also suitable if the previous revision contains that image. The Deployment uses the same H2 service and volume. If you run `demo.sh up` again, it applies the image recorded in `.run/deploy/images.tsv`.
