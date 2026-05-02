//> using scala "3.9.0-RC1-bin-20260501-0c8c581-NIGHTLY"
//> using jvm "zulu:25.0.3"
//> using options "-language:experimental.captureChecking"
//> using options "-Ycc-verbose"
//> using options "-Yexplicit-nulls"
//> using options "-experimental"
// Note: -language:experimental.safe is NOT applied globally because non-safe-mode
// files (DaprRuntime, JsonCodec, internal/*) need to use @scala.caps.assumeSafe.
// Safe mode is enabled per-file via: import language.experimental.safe
//> using dep "io.dapr:dapr-sdk:1.13.3"
//> using dep "com.lihaoyi::upickle:3.3.1"
//> using dep "org.scalameta::munit:1.0.3"
//> using dep "com.dimafeng::testcontainers-scala-munit:0.41.4"
//> using dep "io.dapr:testcontainers-dapr:0.13.3"
//> using dep "org.testcontainers:testcontainers:1.20.4"
