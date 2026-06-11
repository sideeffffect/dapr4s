//> using target.platform "jvm"
// JVM-only test-scope dependencies (testcontainers, for the Docker-based integration suites).
//
// Two directives combine to scope these to "JVM, test scope":
//   - the `.test.scala` filename suffix puts the whole file in test scope, so plain `using dep`
//     lines below are test-only — deliberately NOT `using test.dep`, which is not
//     platform-scoped and would leak the deps into the Scala.js test build (empirically
//     verified);
//   - the `target.platform "jvm"` directive scopes them to the JVM platform.
//
//> using dep "com.dimafeng::testcontainers-scala-munit:0.43.6"
//> using dep "io.dapr:testcontainers-dapr:1.17.2"
// testcontainers-scala 0.43.6 pulls TC 1.21.1; testcontainers-dapr 1.17.2 pulls TC 1.21.4.
// Both resolve to 1.21.4 with no conflict. Upgrade to testcontainers-scala 0.44+ only after
// testcontainers-dapr ships a TC 2.x-compatible release (fix merged to dapr/java-sdk master,
// awaiting release as v1.18.0).
