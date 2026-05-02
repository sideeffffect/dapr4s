//> using scala "3.5.2"
//> using options "-language:experimental.captureChecking"
//> using dep "io.dapr:dapr-sdk:1.13.3"
//> using dep "com.lihaoyi::upickle:3.3.1"
//> using dep "org.scalameta::munit:1.0.3"
//> using dep "io.dapr:testcontainers-dapr:0.13.3"
//> using dep "org.testcontainers:testcontainers:1.20.4"
// Future enhancement: add "-Ycc" (capture checking) once available in stable Scala 3.
// This flag enables full enforcement of capability escape via `^` annotations.
// Currently it is a nightly-only flag and is NOT available in Scala 3.5.2.
// -Ycc-verbose can be added alongside -Ycc for diagnostic output.
