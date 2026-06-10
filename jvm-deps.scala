// JVM-only dependencies, kept out of project.scala on purpose.
//
// scala-cli cannot scope dependency directives to a platform: a `//> using dep` directive
// applies to every platform of the build no matter which file it appears in (even a file
// tagged `//> using target.platform jvm`). Keeping the Dapr Java SDK and testcontainers here
// and excluding this file from Scala.js invocations is what keeps the published _sjs1_3 POM
// free of JVM-only artifacts.
//
// Default invocations (`scala-cli compile|test|publish .`) include this file, so the JVM
// workflow is unchanged. Every Scala.js invocation must exclude it:
//
//   scala-cli compile --js . --exclude jvm-deps.scala
//   scala-cli test    --js . --exclude jvm-deps.scala
//   scala-cli publish --js . --exclude jvm-deps.scala
//
//> using dep "io.dapr:dapr-sdk:1.17.2"
//> using dep "io.dapr:dapr-sdk-actors:1.17.2"
//> using dep "io.dapr:dapr-sdk-workflows:1.17.2"
//> using test.dep "com.dimafeng::testcontainers-scala-munit:0.43.6"
//> using test.dep "io.dapr:testcontainers-dapr:1.17.2"
// testcontainers-scala 0.43.6 pulls TC 1.21.1; testcontainers-dapr 1.17.2 pulls TC 1.21.4.
// Both resolve to 1.21.4 with no conflict. Upgrade to testcontainers-scala 0.44+ only after
// testcontainers-dapr ships a TC 2.x-compatible release (fix merged to dapr/java-sdk master,
// awaiting release as v1.18.0).
