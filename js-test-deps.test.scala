//> using target.platform "scala-js"
// Scala.js-only TEST-scope dependencies — the JS twin of jvm-test-deps.test.scala.
//
// Two directives combine to scope these to "Scala.js, test scope":
//   - the `.test.scala` filename suffix puts the whole file in test scope, so plain `using dep`
//     lines below are test-only — deliberately NOT `using test.dep`, which is not
//     platform-scoped and would leak the deps into the JVM test build;
//   - the `target.platform "scala-js"` directive scopes them to the Scala.js platform.
//
// ==ScalablyTyped facades for the Node testcontainers libraries==
//
// The dapr4s JS integration suites drive a real Dapr sidecar from inside the test runtime via
// the Node `testcontainers` library and `@dapr/testcontainer-node` — the exact twin of how the
// JVM suites use `io.dapr:testcontainers-dapr` (see test/js/integration/DaprJsContainer.scala).
// These two facade roots are GENERATED from the npm packages pinned in package.json by
// scripts/generate-st-facades.sh, into the same `dapr4styped.*` package as the main facades,
// and published to the LOCAL ivy repository (~/.ivy2/local/org.scalablytyped/...). The digest
// suffix is the converter's deterministic digest of the package's resolved subtree; update it
// here and in the EXPECTED_TC/EXPECTED_DAPRTC variables of scripts/generate-st-facades.sh
// together (the script fails loudly on drift), exactly like the main roots in js-deps.scala.
//
// Unlike the js-deps.scala facades (MAIN scope, compiled into and embedded in the published
// dapr4s_sjs1_3 jar) these are TEST scope: `scala-cli publish --js` compiles main scope only, so
// they never reach the published POM or the embedded artifact, and scripts/embed-st-facades.sh
// only stages the three MAIN roots' transitive closure (which does not include testcontainers).
// Declared as plain `dep` (not `compileOnly`): test deps are absent from every published POM
// regardless, and a regular dep keeps the whole facade closure (dockerode, docker-modem, …) on
// the Wasm `test` link classpath without the --jar dance the compileOnly main facades need.
//> using dep "org.scalablytyped::testcontainers::11.5.1-fb5244"
//> using dep "org.scalablytyped::dapr__testcontainer-node::0.5.1-13e160"
