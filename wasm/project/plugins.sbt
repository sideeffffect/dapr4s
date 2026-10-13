// The scala-wasm fork of sbt-scalajs (WasmComponent module kind, WIT bindings via wit-bindgen-scala).
// It shares classes and keys with org.scala-js:sbt-scalajs, which is why this is a separate build
// from the repo root (see build.sbt). 1.22.1-wasm.6 is the newest on Maven Central (2026-10-13).
addSbtPlugin("io.github.scala-wasm" % "sbt-scalajs" % "1.22.1-wasm.6")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
// The version of the publishLocal-able wasm artifacts: derived from git exactly like the root build's (sbt-ci-release
// uses sbt-dynver), so a `publishLocal` of both builds at one commit yields one version.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

// The WIT glue generator (project/WitGlue.scala): scalameta parses wit-bindgen-scala's output, ujson
// reads the resolved WIT world (`wasm-tools component wit --json`).
libraryDependencies ++= Seq(
  "org.scalameta" %% "scalameta" % "4.17.4",
  "com.lihaoyi" %% "ujson" % "4.4.3",
)
