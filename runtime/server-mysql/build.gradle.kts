/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import io.quarkus.gradle.tasks.QuarkusBuild
import io.quarkus.gradle.tasks.QuarkusRun

plugins {
  alias(libs.plugins.quarkus)
  id("org.kordamp.gradle.jandex")
  id("polaris-runtime")
  id("polaris-license-report")
}

val quarkusRunner by
  configurations.creating { description = "Used to reference the generated runner-jar" }

dependencies {
  implementation(project(":polaris-runtime-service"))

  runtimeOnly(project(":polaris-relational-jdbc"))
  runtimeOnly(project(":polaris-extensions-federation-hadoop"))
  runtimeOnly(project(":polaris-extensions-auth-opa"))
  runtimeOnly(project(":polaris-extensions-auth-ranger"))

  if ((project.findProperty("NonRESTCatalogs") as String?)?.contains("HIVE") == true) {
    runtimeOnly(project(":polaris-extensions-federation-hive"))
  }

  if ((project.findProperty("NonRESTCatalogs") as String?)?.contains("BIGQUERY") == true) {
    runtimeOnly(project(":polaris-extensions-federation-bigquery"))
  }

  // The MySQL JDBC driver is GPL-licensed and therefore cannot be distributed with Apache
  // Polaris under the ASF third-party license policy (see issue #2491 discussion). The driver
  // is bundled only when the consumer explicitly opts in with:
  //     ./gradlew :polaris-server-mysql:build -PincludeMysqlDriver=true
  // The release/source artifacts produced without this flag contain no MySQL driver.
  if (project.hasProperty("includeMysqlDriver")) {
    runtimeOnly("io.quarkus:quarkus-jdbc-mysql")
  }

  // enforce the Quarkus _platform_ here, to get a consistent and validated set of dependencies
  implementation(enforcedPlatform(libs.quarkus.bom))
  implementation("io.quarkus:quarkus-container-image-docker")

  // intTest deps go through `testImplementation` — the polaris-runtime plugin makes
  // `intTestImplementation` extend from it, mirroring the runtime/service setup.
  testImplementation(testFixtures(project(":polaris-runtime-service")))
  testImplementation(project(":polaris-runtime-test-common"))
  testImplementation(project(":polaris-container-spec-helper"))
  testImplementation(enforcedPlatform(libs.quarkus.bom))
  testImplementation("io.quarkus:quarkus-junit")
  testImplementation("io.quarkus:quarkus-junit-mockito")
  testImplementation("io.quarkus:quarkus-rest-client")
  testImplementation("io.quarkus:quarkus-rest-client-jackson")
  testImplementation("io.rest-assured:rest-assured")
  testImplementation(platform(libs.iceberg.bom))
  testImplementation("org.apache.iceberg:iceberg-api")
  testImplementation("org.apache.iceberg:iceberg-core")
  testImplementation("org.apache.iceberg:iceberg-api:${libs.versions.iceberg.get()}:tests")
  testImplementation("org.apache.iceberg:iceberg-core:${libs.versions.iceberg.get()}:tests")
  testImplementation(platform(libs.testcontainers.bom))
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-mysql")

  // This dependency brings in RESTEasy Classic, which conflicts with Quarkus RESTEasy Reactive;
  // it must not be present during Quarkus augmentation otherwise Quarkus tests won't start.
  "intTestRuntimeOnly"(libs.keycloak.admin.client)
}

// The MySQL JDBC driver is GPL-licensed and is not bundled into the release artifact
// (see ASF Category X in issue #2491 discussion). In the default build the Quarkus packaging
// tasks are silently skipped so CI does not fail trying to build a driver-less runner; the
// full runner is produced only when the caller opts in with `-PincludeMysqlDriver=true`.
listOf("quarkusAppPartsBuild", "quarkusDependenciesBuild", "quarkusBuild").forEach { name ->
  tasks
    .matching { it.name == name }
    .configureEach {
      onlyIf {
        val hasFlag = project.hasProperty("includeMysqlDriver")
        if (!hasFlag) {
          logger.lifecycle(
            "Skipping $name: requires -PincludeMysqlDriver=true because the MySQL JDBC " +
              "driver is GPL-licensed and cannot be bundled with Apache Polaris. See " +
              "runtime/server-mysql/README.md."
          )
        }
        hasFlag
      }
    }
}

// The license-report plugin validates that all runtime-classpath artifacts are listed
// in the root LICENSE file. When the MySQL JDBC driver is opted in, it is deliberately
// absent from LICENSE (GPL — never bundled into the ASF release). Disable the validation
// for opt-in local/dev builds. Without the flag the driver is not on the classpath, so
// the validation passes normally.
if (project.hasProperty("includeMysqlDriver")) {
  listOf("generateLicenseReport", "checkLicense", "licenseReportZip").forEach { name ->
    tasks.matching { it.name == name }.configureEach { enabled = false }
  }
}

quarkus {
  quarkusBuildProperties.put("quarkus.package.jar.type", "fast-jar")
  // Pull manifest attributes from the "main" `jar` task to get the
  // release-information into the jars generated by Quarkus.
  quarkusBuildProperties.putAll(
    provider {
      tasks
        .named("jar", Jar::class.java)
        .get()
        .manifest
        .attributes
        .map { e -> "quarkus.package.jar.manifest.attributes.\"${e.key}\"" to e.value.toString() }
        .toMap()
    }
  )
}

tasks.register("run") {
  group = "application"
  description = "Runs the Apache Polaris server (MySQL variant)"
  dependsOn("quarkusRun")
}

tasks.named<QuarkusRun>("quarkusRun") {
  jvmArgs =
    listOf(
      "-Dpolaris.bootstrap.credentials=POLARIS,root,s3cr3t",
      "-Dquarkus.console.color=true",
      "-Dpolaris.features.\"ALLOW_INSECURE_STORAGE_TYPES\"=true",
      "-Dpolaris.features.\"SUPPORTED_CATALOG_STORAGE_TYPES\"=[\"FILE\",\"S3\",\"GCS\",\"AZURE\"]",
      "-Dpolaris.readiness.ignore-severe-issues=true",
      "-Dpolaris.features.\"DROP_WITH_PURGE_ENABLED\"=true",
    )
}

val quarkusBuild = tasks.named<QuarkusBuild>("quarkusBuild")

// Configuration to expose distribution artifacts
val distributionElements by
  configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
  }

val licenseNoticeElements by
  configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
  }

// Expose runnable jar via quarkusRunner configuration for integration-tests that require the
// server.
artifacts {
  add(quarkusRunner.name, provider { quarkusBuild.get().fastJar.resolve("quarkus-run.jar") }) {
    builtBy(quarkusBuild)
  }
  add("distributionElements", layout.buildDirectory.dir("quarkus-app")) { builtBy("quarkusBuild") }
  add("licenseNoticeElements", layout.projectDirectory.dir("distribution"))
}

tasks.withType(Test::class.java).configureEach {
  if (System.getenv("AWS_REGION") == null) {
    environment("AWS_REGION", "us-west-2")
  }
  environment("POLARIS_BOOTSTRAP_CREDENTIALS", "POLARIS,test-admin,test-secret")
  jvmArgs("--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED")
  // Needed for Subject.getSubject to work under Java 21+
  systemProperty("java.security.manager", "allow")
}

tasks.named<Test>("intTest").configure {
  // intTest depends on quarkusBuild which is skipped when `-PincludeMysqlDriver=true` is not
  // set (see the `onlyIf` block above); mirror the same skip condition here so the Test task
  // short-circuits in the default build instead of failing to find a runner jar.
  onlyIf {
    val hasFlag = project.hasProperty("includeMysqlDriver")
    if (!hasFlag) {
      logger.lifecycle(
        "Skipping intTest: requires -PincludeMysqlDriver=true. See runtime/server-mysql/README.md."
      )
    }
    hasFlag
  }
  val logsDir = project.layout.buildDirectory.get().asFile.resolve("logs")
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      // Propagate the security-manager flag and bootstrap credentials to the spawned
      // Quarkus app JVM (`@QuarkusIntegrationTest` forks a process for the built runner).
      listOf(
        "-Dquarkus.test.arg-line=-Djava.security.manager=allow " +
          "-Dpolaris.bootstrap.credentials=POLARIS,test-admin,test-secret",
        "-Dquarkus.log.file.path=${logsDir.resolve("polaris.log").absolutePath}",
      )
    }
  )
  doFirst { logsDir.deleteRecursively() }
}
