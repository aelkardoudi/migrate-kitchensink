# Deploy Kitchensink on kind

In IntelliJ IDEA, select **Kitchensink - Kubernetes** in the top run selector and click **Run**. It starts WildFly and H2 on the `kind-kitchensink` cluster, then serves the application at <http://127.0.0.1:18201/kitchensink/>. Click **Stop** once to stop both workloads while keeping the database volume and credentials; allow shutdown to finish before forcing termination. The images must already have been built as described below.

**Kitchensink - Stop** also stops workloads started outside the IDE. **Kitchensink - Pods** prints their status. For the live pod tree and logs, open **View → Tool Windows → Services**, choose the `migrate-kitchensink-dev` namespace under `kind-kitchensink`, and expand **Workloads → Pods**.

WildFly 28.0.1.Final serves the WAR built from `modules/kitchensink/`. H2 2.1.214 runs in a StatefulSet and stores its data in the `h2-data` volume. The WildFly image configures the WAR to connect to H2 over TCP and validate the schema that H2 creates.

You need a JDK, Maven, Docker with BuildKit, kind, kubectl, and OpenSSL.

1. From the repository root, build the WAR and the images.

   ```sh
   mvn clean package
   ./deploy/build-images.sh
   ```

2. Use the `kitchensink` kind cluster. If it doesn't exist, create it with `kind create cluster --name kitchensink`. The command below loads the images, creates the H2 credentials, and applies the manifest in the `migrate-kitchensink-dev` namespace.

   ```sh
   ./deploy/demo.sh up
   ```

3. Forward a local port to WildFly.

   ```sh
   ./deploy/demo.sh forward
   ```

   Open the [Kitchensink UI](http://127.0.0.1:18201/kitchensink/) or the [member list](http://127.0.0.1:18201/kitchensink/rest/members). Keep the port-forward command running while you use the application.

`./deploy/demo.sh status` lists the Kubernetes resources. `./deploy/demo.sh stop` stops WildFly and H2 while keeping the data and credentials. Run `up` again to restart them. Use `--namespace migrate-kitchensink-test` for a separate database, or `forward --port 18202` to choose a different port. To use another local kind cluster, set `KS_KIND_CLUSTER` to its name before running `demo.sh`.

The build verifies the WildFly distribution's SHA-256 checksum. To reuse a local archive, set `KS_WILDFLY_ZIP` to its path before running `build-images.sh`. The script writes the local image references to `.run/deploy/images.tsv`. `demo.sh up` loads those images into kind and inserts their references into the manifest before applying it.
