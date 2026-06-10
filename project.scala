//> using scala "3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY"
//> using platform "jvm" "scala-js"
//> using jvm "zulu:25.0.3"
//> using jsEsVersionStr "es2017"
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
// platform; select Scala.js with `--js` (and add `--exclude jvm-deps.scala`, see below).
// jsEsVersionStr es2017 is required by js.async/js.await (used by the JS internal layer).
//
// JVM-only dependencies (the Dapr Java SDK and testcontainers) live in jvm-deps.scala, NOT
// here: scala-cli has no platform-scoped dependency directives (deps declared in a
// `//> using target.platform jvm` file still leak into the Scala.js build and would pollute
// the published _sjs1_3 POM). JS invocations exclude that file: `--exclude jvm-deps.scala`.
//
// scala-java-time provides java.time on Scala.js (java.time.Instant is part of the public
// JobsCapability/Models API); on the JVM it is a thin shim over the JDK and harmless.
//> using dep "io.github.cquiroz::scala-java-time::2.6.0"
//> using test.dep "org.scalameta::munit::1.3.0"
//> using test.dep "com.lihaoyi::upickle::3.3.1"
//> using publish.organization "com.github.sideeffffect"
//> using publish.name "dapr4s"
//> using publish.computeVersion "git:dynver"
//> using publish.url "https://github.com/sideeffffect/dapr4s/"
//> using publish.license "Apache-2.0"
//> using publish.developer "sideeffffect|Ondra Pelech|https://github.com/sideeffffect/"
//> using publish.repository "central"
