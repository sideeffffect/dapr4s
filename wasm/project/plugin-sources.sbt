// The sbt-dapr4s-wasm plugin (wasm/sbt-plugin) is compiled straight into this meta-build, so dapr4s's own test
// components build exactly like an external app that adds the published plugin. The same sources are published
// (publishLocal only) by the `sbtDapr4sWasm` project in ../build.sbt.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "sbt-plugin" / "src" / "main" / "scala"
