//> using scala "3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY"
//> using platform "jvm" "scala-js"
//> using jvm "zulu:25.0.3"
//> using options "-language:experimental.captureChecking"
//> using options "-language:experimental.pureFunctions"
//> using options "-Ycc-verbose"
//> using options "-Yexplicit-nulls"
//> using options "-experimental"
//> using options "-Wconf:any:error"
// Note: -language:experimental.safe is NOT applied globally because non-safe-mode
// files (Dapr, JsonCodec, internal/*) need to use @scala.caps.assumeSafe.
// Safe mode is enabled per-file via: import language.experimental.safe
//
// Platforms: "jvm" is listed first, so plain `scala-cli compile/test .` builds the JVM
// platform; select Scala.js with `--js` (no extra flags needed).
//
// Platform-specific dependencies AND platform-specific settings live in dedicated files,
// scoped by a `target.platform` directive (a `using dep`/`using js*` directive in a
// platform-tagged file applies only to that platform's build):
//   - jvm-deps.scala            — Dapr Java SDK (JVM, main scope)
//   - jvm-test-deps.test.scala  — testcontainers (JVM, test scope via the .test.scala suffix)
//   - js-deps.scala             — Scala.js deps (facades, scala-java-time) + js* settings
//   - js-test-deps.test.scala   — Scala.js test-scope deps (none yet; placeholder for symmetry)
// Only cross-platform deps belong in this file (the `::version` double-colon form).
//> using test.dep "org.scalameta::munit::1.3.0"
//> using test.dep "com.lihaoyi::upickle::3.3.1"
//> using publish.organization "com.github.sideeffffect"
//> using publish.name "dapr4s"
//> using publish.computeVersion "git:dynver"
//> using publish.url "https://github.com/sideeffffect/dapr4s/"
//> using publish.license "Apache-2.0"
//> using publish.developer "sideeffffect|Ondra Pelech|https://github.com/sideeffffect/"
//> using publish.repository "central"
