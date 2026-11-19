# Kitchensink Member Backfill

This Java 21 Spring Batch job copies the existing members in H2 `PUBLIC.MEMBER` to `kitchensink.members` in MongoDB. Run it once while Camel continues to replicate inserts. The job includes the baseline member with ID `0`.

1. The first step stores an inclusive upper ID in the job execution context. It uses `maxMemberId` when supplied and `MAX(ID)` from H2 by default. Hibernate allocates IDs in blocks, so a transaction can commit later with a smaller ID. Camel still receives that insertion through the H2 outbox.
2. The copy step reads pages in primary-key order, with 100 rows per chunk by default. Each document keeps the numeric H2 ID and the original name, email and phone number. Batch and Camel use the same MongoDB write operation. An identical document counts as success. Divergent data fails the job and preserves the existing document.
3. Spring Batch saves checkpoints in a separate H2 file on the job's Kubernetes volume. MongoDB writes are acknowledged before Spring Batch commits the chunk checkpoint. A retry can therefore see documents from the previous attempt and safely confirm them again. Camel owns outbox acknowledgements.
4. The `verifyBackfillMembers` step compares the exact counts up to and including the saved `maxMemberId`. The `verifyAllMembers` step then compares the exact totals across the whole H2 table and MongoDB collection. Each step accepts a stable gap only when it is fully explained by outbox IDs in its scope that are still absent from MongoDB. Spring Batch retries an unexplained gap before failing the step. Restarting the run resumes the failed step and retains the completed steps.

## Build and run

Build from the repository root with Java 21. Start MongoDB and the Camel relay from this revision before launching the job so Liquibase has applied the target schema.

```sh
mvn -Pbatch -pl modules/kitchensink-member-backfill -am clean verify
./deploy/member-backfill.sh build
./deploy/member-backfill.sh run --run-id history-01 --namespace migrate-kitchensink-test
```

Add `--max-member-id 500` to copy members up to and including ID `500`. The Spring Batch parameter is `maxMemberId=500` when invoking the executable directly.

A partial copy can pass `verifyBackfillMembers` and fail `verifyAllMembers` when there are historical members beyond the bound that are still missing. The completed copy remains saved when the global step fails.

Retry a job that reports `FAILED` with the same `--run-id`, the same choice of `--max-member-id` and the same chunk size to resume from the last committed chunk. Omit the option on retry if the first attempt used the automatic bound. A pod terminated before it recorded a final status can leave the execution `STARTED`. Use a new run ID to rescan safely after that Kubernetes Job ends. See the [deployment guide](../../deploy/README.md#run-the-spring-batch-member-backfill) for status and logs.

## Check global counts independently

Use the standalone Java verifier in a separate Kubernetes Job for an independent exact count check after the Spring Batch job.

```sh
./deploy/member-backfill.sh verify --namespace migrate-kitchensink-test
```

The verifier compares the exact total row count in H2 `PUBLIC.MEMBER` with the exact total document count in MongoDB `kitchensink.members`, regardless of the backfill's `maxMemberId`. It logs both totals and retries briefly if they differ to allow Camel to catch up. It exits with `0` when the totals match, `1` when they still differ, or `2` on a configuration or database read error. To run it directly, supply `KS_H2_JDBC_URL`, `KS_H2_PASSWORD` and `KS_MONGODB_URI`, then pass `verify` to the executable JAR. `KS_H2_USER` defaults to `members_backfill`.
