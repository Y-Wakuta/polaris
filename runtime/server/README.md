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

# Polaris Quarkus Server

This module contains the Quarkus-based Polaris server main artifact.

## Archive distribution

Building this module will create a zip/tar distribution with the Polaris server.

To build the distribution, you can use the following command:

```shell
./gradlew :polaris-server:build
```

You can manually unpack and run the distribution archives:

```shell
cd runtime/server/build/distributions
unzip polaris-server-<version>.zip
cd polaris-server-<version>
java -jar quarkus-run.jar
```

## Docker image

To also build the Docker image, you can use the following command (a running Docker daemon is
required):

```shell
./gradlew \
  :polaris-server:assemble \
  :polaris-server:quarkusAppPartsBuild --rerun \
  -Dquarkus.container-image.build=true
```

If you need to customize the Docker image, for example to push to a local registry, you can use the
following command:

```shell
./gradlew \
  :polaris-server:assemble \
  :polaris-server:quarkusAppPartsBuild --rerun \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.registry=localhost:5001 \
  -Dquarkus.container-image.group=apache \
  -Dquarkus.container-image.name=polaris-local
```

## Building a MySQL-capable server (custom downstream build)

> **Note**: The official Polaris release artifacts do not include the MySQL JDBC driver because the driver is GPL-licensed (ASF Category X). The instructions in this section produce a *custom downstream build* that bundles the driver from your own source tree; the resulting runner is not an official Apache Polaris artifact.

### Build

Add the MySQL JDBC driver to the runner with the `-PincludeMysqlDriver=true` Gradle property:

```shell
./gradlew :polaris-server:assemble -PincludeMysqlDriver=true
```

The Docker image can be built the same way with `-Dquarkus.container-image.build=true`. To avoid overwriting the GPL-free image, pass a distinct image name:

```shell
./gradlew \
  :polaris-server:assemble \
  :polaris-server:quarkusAppPartsBuild --rerun \
  -PincludeMysqlDriver=true \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.name=polaris-mysql
```

When `-PincludeMysqlDriver=true` is set, the build script also disables the license-report tasks (the GPL driver is intentionally absent from `LICENSE`).

### Runtime configuration

MySQL is exposed as a Quarkus *named* datasource (`mysql`) that is declared inactive by default. To use the MySQL backend at runtime, set the following properties (or the equivalent environment variables, e.g. `QUARKUS_DATASOURCE_MYSQL_JDBC_URL`):

```properties
polaris.persistence.type=relational-jdbc
polaris.persistence.relational.jdbc.database-type=mysql

quarkus.datasource.mysql.active=true
quarkus.datasource.mysql.jdbc.url=jdbc:mysql://<host>:3306/POLARIS_SCHEMA
quarkus.datasource.mysql.username=<your-username>
quarkus.datasource.mysql.password=<your-password>
```

PostgreSQL, CockroachDB and H2 continue to use the default (unnamed) datasource and require no changes to existing configuration.

### MySQL server requirements

The MySQL server must be started with `lower_case_table_names=1`. Polaris generates SQL that mixes UPPERCASE table identifiers (e.g. `POLICY_MAPPING_RECORD`) and lowercase ones (e.g. `idempotency_records`), and Linux MySQL is case-sensitive by default. This setting must be configured on first initialization (when the data directory is empty); it cannot be changed later.

### Bootstrapping the MySQL backend

The Polaris admin tool does not currently bundle the MySQL JDBC driver, so the standard admin-tool `bootstrap` flow does not apply. Instead, configure the Polaris server to bootstrap itself on first startup:

```properties
polaris.persistence.auto-bootstrap-types=in-memory,relational-jdbc
polaris.realm-context.realms=<your-realm>
```

```shell
POLARIS_BOOTSTRAP_CREDENTIALS=<realm>,<client-id>,<client-secret>
```

The server will execute `mysql/schema-v4.sql` and create the root principal automatically. Run a single replica for the initial bootstrap to avoid races; the bootstrap log line confirms completion.

**Auto-bootstrap on persistent backends is not yet idempotent** ([apache/polaris#2324](https://github.com/apache/polaris/issues/2324)): re-running the server with these settings against an already-bootstrapped database fails on startup with `metastore manager has already been bootstrapped`. Treat the auto-bootstrap startup as a one-time operation until #2324 is resolved.
