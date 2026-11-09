# mpl-db-migrations

Operational scripts use Bash (3.2+), jq and curl; Java 21 runs the application, migration runner and integration tests. Python is not required. Run scripts with `bash scripts/<name>.sh`.

Independent Java 21 / Liquibase 5.0.4 migration artifact for **user_service_db**. No Spring Boot dependency, application startup, Keycloak schema access or second application is involved. PostgreSQL JDBC is pinned to 42.7.14. Credentials are supplied to each execution, never baked into the image.

## Build and test

```sh
./mvnw -B -ntp clean verify                 # unit + disposable PostgreSQL Testcontainers
./mvnw -B -ntp package                      # unit tests + standalone runner JAR
java -jar target/mpl-db-migrations-1.0.0-runner.jar validate
docker build -t mpl-db-migrations:1.0.0 .
```

Set `DB_URL=jdbc:postgresql://HOST:PORT/user_service_db`, `DB_MIGRATION_USERNAME=app_migrator`, and `DB_MIGRATION_PASSWORD` through the secret manager/environment. Never use postgres, app_runtime or keycloak. The runner checks database name, rejects superusers and checks the entire existing history before execution. Only a single host/database is accepted; optional `sslmode=disable|require|verify-ca|verify-full` is supported. Certificate locations can be configured with the PostgreSQL driver's standard environment/default certificate files; do not embed credentials or arbitrary JDBC options in the URL.

Native local workflows can explicitly read the existing backend dotenv file without modifying it:

```sh
bash scripts/migrate.sh --env-file ../mpl-be/.env verify-history
bash scripts/migrate.sh --env-file ../mpl-be/.env validate
bash scripts/migrate.sh --env-file ../mpl-be/.env status
bash scripts/migrate.sh --env-file ../mpl-be/.env updateSQL > migration-review.sql
# After SQL review and backup/restore readiness:
bash scripts/migrate.sh --env-file ../mpl-be/.env update
bash scripts/migrate.sh --env-file ../mpl-be/.env verify
```

`verify-history` uses a read-only JDBC connection; it checks identities and checksums without running Liquibase commands against existing data. `verify` additionally rejects pending required migrations. API runtime has no migration dependency and must wait for this job. Runtime DML grants/bootstrap remain in mpl-be's integration Compose. Bootstrap scripts initialize only fresh volumes; this repository does not reset or recreate an existing database.

```sh
docker run --rm --env-file /secure/migration.env mpl-db-migrations:1.0.0 validate
docker run --rm --env-file /secure/migration.env mpl-db-migrations:1.0.0 updateSQL
docker run --rm --env-file /secure/migration.env mpl-db-migrations:1.0.0 update
# Or from mpl-be, using Docker internal DNS:
docker compose run --rm migration validate
docker compose run --rm migration updateSQL
docker compose run --rm migration update
docker compose run --rm migration verify
```

## Migration history compatibility

`tests/baseline-databasechangelog.csv` records a read-only 2026-11-09 query of the existing local application database: all five schema changesets, exact IDs, author `mpl-team`, `db/changelog/changes/...` filenames, version-9 checksums and execution order. Unit tests parse the packaged classpath master and compare every recorded identity and checksum. Actual changeset files, including the opt-in seed, were copied byte-for-byte. No logicalFilePath was needed. The master retains the same valid include order and database preconditions; only a pre-existing invalid `inclide` entry pointing at a nonexistent seed file was removed. That invalid entry caused the original PostgreSQL integration baseline to fail.

The old backend Maven plugin used a filesystem-prefixed master path; a different environment may have recorded different identities. The runner deliberately refuses such history instead of applying changes twice. Inspect that environment's history and review an explicit reconciliation before proceeding. Never use clearCheckSums or changelogSync to conceal a mismatch. The runner does not expose those operations or dropAll. Production history has not been inspected.

New changes append immutable files and master includes. Never edit an applied changeset. Prefer expand-contract, and test old/new API versions against the expanded schema before declaring rollback compatibility. The current backend needs changesets 001–005.

## Controlled rollback

```sh
bash scripts/migrate.sh --env-file ../mpl-be/.env rollbackSQL 1 > rollback-review.sql
```

This prints a normalized SQL SHA-256 on stderr. Review SQL, stop incompatible writers, establish an independently verified backup/restore plan, and explicitly authorize the count and database. Export these separately through your approved job environment:

```sh
export MPL_ROLLBACK_APPROVAL=rollback:1:user_service_db
export MPL_BACKUP_REFERENCE=YOUR_VERIFIED_BACKUP_REFERENCE
export MPL_ROLLBACK_SQL_SHA256=THE_REVIEWED_SHA256
bash scripts/migrate.sh --env-file ../mpl-be/.env rollback 1
```

Execution regenerates SQL and refuses a hash mismatch. Rollback is never triggered by an API failure. Rollback 005 removes registration storage and is incompatible with the current API; this is exercised only on disposable Testcontainers in tests. Schema rollback does **not** restore deleted data. IAM rollback is a different operation. A target with an unsupported checksum version is blocked for compatibility review.

## Optional development seed

The original seed remains opt-in, requires an already-existing disabled Keycloak identity, and never creates credentials. Set `MIGRATION_CONTEXTS=local,development-seed`, `MPL_DEVELOPMENT_SEED_APPROVED=true` and a real `KEYCLOAK_DEV_SEED_USER_ID`. Keep that UUID available for checksum verification of any applied seed. Production, local and test defaults exclude it. Do not enable it in production. It creates the original fixed INACTIVE profile; use the ADMIN API to activate only after verifying the identity.

## Trade-offs

Independent migration and rollback execution, separate credentials and controlled release ordering cost a repository/image, API/schema coordination and extra pipeline steps. Incompatible rollback can break running APIs. This extraction adds no application tables, datasources, database architecture redesign or migration-history rewrite.
