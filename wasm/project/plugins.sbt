// The scala-wasm fork of sbt-scalajs (WasmComponent module kind, WIT bindings via wit-bindgen-scala).
// It shares classes and keys with org.scala-js:sbt-scalajs, which is why this is a separate build
// from the repo root (see build.sbt).
// 1.22.1-wasm.7-dapr4s-local is NOT on Maven Central: ../scripts/wasm/build-scala-wasm.sh builds it from
// scala-wasm's `scala-wasm` branch + the open upstream PRs dapr4s needs, and publishLocal's it (the CI
// wasm leg runs it, cached). Back to a Central release (1.22.1-wasm.6 was the last one) once scala-wasm
// releases those fixes.
addSbtPlugin("io.github.scala-wasm" % "sbt-scalajs" % "1.22.1-wasm.7-dapr4s-local")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
// Same as the root build: sbt-ci-release (sbt-dynver version from git, so one commit gives core and the wasm artifacts
// one version; publishSigned + Sonatype for the release workflow, which keeps Central publishing OFF for now).
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
