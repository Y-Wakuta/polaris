---
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
title: Relational JDBC
type: docs
weight: 100
---

This implementation leverages Quarkus for datasource management and supports configuration through
environment variables or JVM -D flags at startup. For more information, refer to the [Quarkus configuration reference](https://quarkus.io/guides/config-reference#env-file).

We have 2 options for configuring the persistence backend:

## 1. Relational JDBC metastore with username and password

Using environment variables:

```properties
POLARIS_PERSISTENCE_TYPE=relational-jdbc

QUARKUS_DATASOURCE_USERNAME=<your-username>
QUARKUS_DATASOURCE_PASSWORD=<your-password>
QUARKUS_DATASOURCE_JDBC_URL=<jdbc-url-of-postgres>
```

Using properties file:

```properties
polaris.persistence.type=relational-jdbc
quarkus.datasource.jdbc.username=<your-username>
quarkus.datasource.jdbc.password=<your-password>
quarkus.datasource.jdbc.jdbc-url=<jdbc-url-of-postgres>
```

## 2. AWS Aurora PostgreSQL metastore using IAM AWS authentication

```properties
polaris.persistence.type=relational-jdbc
quarkus.datasource.jdbc.url=jdbc:postgresql://polaris-cluster.cluster-xyz.us-east-1.rds.amazonaws.com:6160/polaris
quarkus.datasource.jdbc.additional-jdbc-properties.wrapperPlugins=iam
quarkus.datasource.username=dbusername
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.additional-jdbc-properties.ssl=true
quarkus.datasource.jdbc.additional-jdbc-properties.sslmode=require
quarkus.datasource.credentials-provider=aws

quarkus.rds.credentials-provider.aws.use-quarkus-client=true
quarkus.rds.credentials-provider.aws.username=dbusername
quarkus.rds.credentials-provider.aws.hostname=polaris-cluster.cluster-xyz.us-east-1.rds.amazonaws.com
quarkus.rds.credentials-provider.aws.port=6160
```

This is the basic configuration. For more details, please refer to the [Quarkus plugin documentation](https://docs.quarkiverse.io/quarkus-amazon-services/dev/amazon-rds.html#_configuration_reference).

## 3. MySQL (driver not bundled)

The Relational JDBC backend supports MySQL 8.0+ through the `polaris-server-mysql` runtime module. Because the MySQL JDBC driver is GPL-licensed, it cannot be distributed with Apache Polaris (see [issue #2491](https://github.com/apache/polaris/issues/2491)); you must build a runner that includes the driver yourself:

```bash
./gradlew :polaris-server-mysql:build -PincludeMysqlDriver=true
```

Once the runner is built, configure it the same way as the PostgreSQL case, pointing to a MySQL server:

```properties
polaris.persistence.type=relational-jdbc
polaris.persistence.relational.jdbc.database-type=mysql
quarkus.datasource.jdbc.url=jdbc:mysql://<host>:3306/POLARIS_SCHEMA
quarkus.datasource.username=<your-username>
quarkus.datasource.password=<your-password>
```

### MySQL server requirements

The MySQL server must be started with `lower_case_table_names=1`.
Polaris generates SQL that mixes UPPERCASE table identifiers (e.g. `POLICY_MAPPING_RECORD`) and lowercase ones (e.g. `idempotency_records`), and Linux MySQL is case-sensitive by default.
This setting must be set on first initialization (when the data directory is empty); it cannot be changed later.

### Bootstrapping the MySQL backend

The Polaris admin tool does not currently bundle the MySQL JDBC driver, so the [Admin Tool]({{% ref "../admin-tool" %}}) `bootstrap` flow described below does not apply to MySQL.
For the MySQL variant, configure the Polaris server to bootstrap itself on first startup by setting:

```properties
polaris.persistence.auto-bootstrap-types=in-memory,relational-jdbc
polaris.realm-context.realms=<your-realm>
```

```bash
POLARIS_BOOTSTRAP_CREDENTIALS=<realm>,<client-id>,<client-secret>
```

The server will execute `mysql/schema-v4.sql` and create the root principal automatically.
Run a single replica for the initial bootstrap to avoid races; the bootstrap log line confirms completion.
**Auto-bootstrap on persistent backends is not yet idempotent** ([apache/polaris#2324](https://github.com/apache/polaris/issues/2324)) — re-running the server with these settings against an already-bootstrapped database fails on startup with "metastore manager has already been bootstrapped".
Treat the auto-bootstrap startup as a one-time operation until #2324 is resolved.

See [`runtime/server-mysql/README.md`](https://github.com/apache/polaris/tree/main/runtime/server-mysql) for the full rationale and build options.

---

The Relational JDBC metastore currently relies on a Quarkus-managed datasource and supports PostgreSQL, H2, and MySQL databases. At this time, the most detailed documentation is provided for PostgreSQL.
Please refer to the documentation here:
[Configure data sources in Quarkus](https://quarkus.io/guides/datasource).

Additionally, the retries can be configured via `polaris.persistence.relational.jdbc.*` properties; please refer to the [Configuring Polaris]({{% ref "../configuration" %}}) section.

## Bootstrapping Polaris

Before using Polaris with the Relational JDBC backend, you must bootstrap the metastore to create the necessary schema and initial realm. This is done using the [Admin Tool]({{% ref "../admin-tool" %}}).

Using Docker:

```bash
docker run --rm -it \
  --env="polaris.persistence.type=relational-jdbc" \
  --env="quarkus.datasource.username=<your-username>" \
  --env="quarkus.datasource.password=<your-password>" \
  --env="quarkus.datasource.jdbc.url=<jdbc-url-of-postgres>" \
  apache/polaris-admin-tool:latest bootstrap -r <realm-name> -c <realm-name>,<client-id>,<client-secret>
```

Using the standalone JAR:

```bash
java \
  -Dpolaris.persistence.type=relational-jdbc \
  -Dquarkus.datasource.username=<your-username> \
  -Dquarkus.datasource.password=<your-password> \
  -Dquarkus.datasource.jdbc.url=<jdbc-url-of-postgres> \
  -jar polaris-admin-tool.jar bootstrap -r <realm-name> -c <realm-name>,<client-id>,<client-secret>
```

For more details on the bootstrap command and other administrative operations, see the [Admin Tool]({{% ref "../admin-tool" %}}) documentation.
