//> using scala "3.9.0-RC1-bin-20260501-0c8c581-NIGHTLY"
//> using jvm "zulu:25.0.3"
//> using options "-language:experimental.captureChecking"
//> using options "-language:experimental.pureFunctions"
//> using options "-Ycc-verbose"
//> using options "-Yexplicit-nulls"
//> using options "-experimental"
//> using options "-Wconf:any:error"
// Note: -language:experimental.safe is NOT applied globally because non-safe-mode
// files (DaprRuntime, JsonCodec, internal/*) need to use @scala.caps.assumeSafe.
// Safe mode is enabled per-file via: import language.experimental.safe
//> using dep "io.dapr:dapr-sdk:1.17.2"
//> using dep "io.dapr:dapr-sdk-actors:1.17.2"
//> using dep "io.dapr:dapr-sdk-workflows:1.17.2"
//> using dep "com.lihaoyi::upickle:3.3.1"
//> using dep "org.scalameta::munit:1.3.0"
//> using dep "com.dimafeng::testcontainers-scala-munit:0.43.6"
//> using dep "io.dapr:testcontainers-dapr:1.17.2"
// testcontainers-scala 0.43.6 pulls TC 1.21.1; testcontainers-dapr 1.17.2 pulls TC 1.21.4.
// Both resolve to 1.21.4 with no conflict. Upgrade to testcontainers-scala 0.44+ only after
// testcontainers-dapr ships a TC 2.x-compatible release (fix merged to dapr/java-sdk master,
// awaiting release as v1.18.0).
