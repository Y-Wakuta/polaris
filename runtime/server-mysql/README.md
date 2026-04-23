<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->

# polaris-server-mysql

A MySQL variant of `polaris-server`. It builds the same Polaris service jar as `polaris-server` but configures the Quarkus datasource with `db-kind=mysql` and sets `polaris.persistence.relational.jdbc.database-type=mysql`.

Relational JDBC MySQL support itself (the `DatabaseType.MYSQL` enum, the `mysql/schema-v4.sql` schema, SQLSTATE handling, etc.) lives in `persistence/relational-jdbc`. This module only assembles the Quarkus runner for the MySQL variant; `quarkus.datasource.db-kind` is fixed at Quarkus build time, which is why a separate runtime module is required.

## Why the MySQL driver is not bundled by default

The MySQL JDBC driver (`com.mysql:mysql-connector-j`) is GPL-licensed, and the MariaDB alternative (`org.mariadb.jdbc:mariadb-java-client`) is LGPL-licensed. Both fall under **ASF Category X** and therefore cannot be distributed with Apache Polaris. See the discussion on [issue #2491](https://github.com/apache/polaris/issues/2491).

As a consequence, the default build of this module produces a runner jar **without a JDBC driver**. Invoking `quarkusBuild` (or any of its dependencies) without the opt-in flag fails fast with a descriptive `GradleException`.

## Opting in locally (development / CI)

Pass `-PincludeMysqlDriver=true` to include `io.quarkus:quarkus-jdbc-mysql` in the runner. This is intended for local development, CI, and users who build their own custom distribution:

```bash
# Build a runner that includes the MySQL JDBC driver:
./gradlew :polaris-server-mysql:build -PincludeMysqlDriver=true

# Run the MySQL integration tests:
./gradlew :polaris-server-mysql:intTest -PincludeMysqlDriver=true
```

Releases and source distributions published by the Apache Polaris project do not carry this flag; the produced artifacts remain driver-free and ASF-compliant.

## Running the server against MySQL

Once a driver-enabled runner jar has been built, point it at a MySQL server:

```bash
java \
  -Dpolaris.persistence.type=relational-jdbc \
  -Dquarkus.datasource.jdbc.url=jdbc:mysql://<host>:3306/POLARIS_SCHEMA \
  -Dquarkus.datasource.username=<user> \
  -Dquarkus.datasource.password=<password> \
  -jar build/quarkus-app/quarkus-run.jar
```

## MySQL server requirements

The MySQL server must be started with `lower_case_table_names=1`.
Polaris generates SQL that mixes UPPERCASE table identifiers (e.g. `POLICY_MAPPING_RECORD`) and lowercase ones (e.g. `idempotency_records`), and Linux MySQL is case-sensitive by default.
This setting must be configured on **first** initialization (when the data directory is empty); it cannot be changed afterwards.

## Bootstrapping the schema and root realm

The Polaris admin tool does not bundle the MySQL JDBC driver (it ships only with the PostgreSQL driver), so the standard `polaris-admin-tool bootstrap` flow is not available for this variant.
Instead, let the Polaris server bootstrap itself on first startup.
Add the following to the runner's configuration:

```properties
polaris.persistence.auto-bootstrap-types=in-memory,relational-jdbc
polaris.realm-context.realms=POLARIS
```

Provide the root credentials via environment variable (or system property), e.g.:

```bash
POLARIS_BOOTSTRAP_CREDENTIALS=POLARIS,<root-client-id>,<root-client-secret>
```

When the server starts against an empty database, it executes `mysql/schema-v4.sql` (selected via `DatabaseType.MYSQL`) and creates the root principal credentials for each realm in `polaris.realm-context.realms`.
Run a single Polaris instance for the first startup to avoid concurrent bootstrap attempts; the "Realm 'X' automatically bootstrapped" log line confirms completion.
**Auto-bootstrap on persistent backends is not yet idempotent** ([apache/polaris#2324](https://github.com/apache/polaris/issues/2324)) — re-running the server with these settings against an already-bootstrapped database fails on startup with "metastore manager has already been bootstrapped".
Treat the auto-bootstrap startup as a one-time operation until #2324 is resolved.
