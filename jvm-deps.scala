//> using target.platform "jvm"
// JVM-only main-scope dependencies (the Dapr Java SDK), kept out of project.scala on purpose.
//
// The `target.platform "jvm"` directive above scopes every `using dep` in this file to the JVM
// platform: `scala-cli compile|test --js .` resolves none of them, which keeps the published
// _sjs1_3 build/POM free of JVM-only artifacts. (This replaces the old `--exclude jvm-deps.scala`
// mechanism — no `--exclude` flags are needed anywhere any more.)
//
// JVM-only *test* dependencies (testcontainers) live in jvm-test-deps.test.scala: `test.dep`
// directives are not platform-scoped, so the test scoping comes from the `.test.scala` filename
// instead.
//
//> using dep "io.dapr:dapr-sdk:1.17.2"
//> using dep "io.dapr:dapr-sdk-actors:1.17.2"
//> using dep "io.dapr:dapr-sdk-workflows:1.17.2"
