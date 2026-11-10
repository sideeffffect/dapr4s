# dapr4s on Mill: Scala 3 nightly + Scala.js + ScalablyTyped

dapr4s moved from sbt to Mill. The migration was first written on top of the Wasm prototype
([PR #45](https://github.com/sideeffffect/dapr4s/pull/45), 2026-10-13, then
[PR #47](https://github.com/sideeffffect/dapr4s/pull/47) for the released upstream versions). Its non-Wasm part was
then ported onto `master` on its own (2026-11-10, [PR #49](https://github.com/sideeffffect/dapr4s/pull/49)), so it can merge before the Wasm prototype
([PR #44](https://github.com/sideeffffect/dapr4s/pull/44)); the Wasm modules move to Mill in #44 after that.
History: scala-cli until 2026-10-12, then sbt (see
[ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md) and
[js.async / JSPI](scala-js-async-jspi-wasm.md) for the sbt notes). This article records what was needed and why.

Versions: Mill **1.3.1** and mill-scalablytyped **0.4.2**, both released, both from Maven Central.

## Parity results (verified against sbt on the same commit)

- `dapr4s_3` / `dapr4s_sjs1_3` POMs: byte-identical to sbt `publishLocal` (version aside), so identical to the
  published POMs. Jar entry sets identical (facade `.sjsir` included).
- ScalablyTyped facade sources: identical to sbt's converter plugin output (3559 files).
- All test legs green: JVM/JS unit + integration.

## Mill facts learned

- **Scala 3.8+ on the JVM**: Mill depends on `org.scala-lang:scala-library:3.x` directly; sbt on
  `scala3-library_3` (which brings the same `scala-library`). Override `scalaLibraryMvnDeps` to keep sbt's
  POM coordinate.
- **Mill's POM renderer** omits `<organization>`, writes an empty `<email>` and orders elements differently. For
  byte-identical POMs, override `pom` with your own renderer (`Dapr4sPom` in `buildsupport.mill`).
- **PublishModule** demands that every `moduleDep` is a `PublishModule`. To keep a never-published module out of
  the POM (the facades), make it a `PublishModule` and filter it out of BOTH `publishXmlDeps` (POM) and
  `publishMvnDeps` (ivy.xml).
- **Scala.js test link bug (Scala >= 3.8)**: `TestScalaJSModule.scalaJSTestDeps` resolved the test bridge
  transitively and PREPENDED it to the link classpath, which brought `scalajs-scalalib_2.13:2.13.x+1.22.0`. The 2.13
  stdlib IR then shadowed the Scala 3 one (first IR file wins): "Referring to non-existent method
  scala.Some.orNull()". Fixed in Mill 1.3.1 ([mill#7786](https://github.com/com-lihaoyi/mill/pull/7786)); dapr4s
  needs no workaround any more.
- **Gotcha**: a module that was a `TestScalaJSModule` only through a removed workaround trait silently becomes a
  JVM test module: `testForked` then runs munit's JS runner on the JVM ("A method defined in a native JavaScript type
  ... has been called"). Mix `TestScalaJSModule` in explicitly (`integration.js` does).
- **JS test parallelism**: Mill's Scala.js test runner uses one Node runtime, so sbt's
  `parallelExecution := false` footgun does not exist. JVM tests run in parallel by default in Mill 1.x
  (`testParallelism`); dapr4s's HTTP-server unit suites need `testParallelism = false` (3 s port timeouts).
- **Filesystem sandbox**: a task may only read files declared through `Task.Source(s)` or `Task.Input`
  ("Reading from ... not allowed during execution"). Subprocesses (`runner().run`, `os.proc`) are not checked.
- **CLI**: several tasks need `+` (`./mill a + b`) or brace expansion; plain `./mill a b` passes `b` as an
  argument to `a`. Commands take named args (`publishM2Local --m2RepoPath <dir>`).
- **Dry-run publishing**: `MILL_TESTS_PUBLISH_DRY_RUN=1` with `SonatypeCentralPublishModule/publishAll`.
  Credentials: `MILL_SONATYPE_USERNAME/PASSWORD`; key: `MILL_PGP_SECRET_BASE64` (base64 of the armored secret
  key) + `MILL_PGP_PASSPHRASE`. Snapshots go to the Central snapshots repository.
- **Versions like sbt-dynver**: `Dynver` in `buildsupport.mill` reproduces sbt-dynver as sbt-ci-release configured
  it (tag → `X.Y.Z`, else `X.Y.Z+<n>-<sha8>[+<timestamp>]-SNAPSHOT`). On a dirty tree the timestamp has minute
  precision, so two Mill runs can compute different versions.
- **JDK**: the build header `//| mill-jvm-version: zulu:25.0.3` makes Mill run on and fork tests with Zulu 25;
  `//| mill-jvm-opts` sets the daemon heap.
- **scalafmt**: `./mill mill.scalalib.scalafmt.ScalafmtModule/scalafmt [--check]` runs the scalafmt CLI in the
  workspace root (it honours `project.excludeFilters`). Add `project.git = true` or it formats `out/`.

## ScalablyTyped

`mill-scalablytyped` (com.github.lolgab) 0.4.2 has everything dapr4s needs:
`scalablyTypedStdLibs` (sbt's `stStdlib`, [#51](https://github.com/lolgab/mill-scalablytyped/pull/51)),
`scalablyTypedOutputPackage` (`stOutputPackage`), and `scalablyTypedNpmCi` (`npm ci` from `package-lock.json`,
re-run when the lock file changes, [#50](https://github.com/lolgab/mill-scalablytyped/pull/50) /
[#52](https://github.com/lolgab/mill-scalablytyped/pull/52)). #52 renamed `scalablyTypedPackageLock` to
`scalablyTypedPackageLockJson` (dapr4s does not override it).

History: 0.4.1 hard-coded stdlib `es6`. Until 0.4.2 the branches called the sbt GenSource plugin's generator,
`org.scalablytyped.converter.internal.ImportTypingsGenSources` (in the `sbt-converter_2.12_1.0` jar, but it uses no
sbt API), from a 2.12 module, then built unreleased mill-scalablytyped commits locally. Options to reproduce sbt
there: `enableScalaJsDefined = Selection.All` for Scala 3, `ignored = {typescript}`,
`organization = org.scalablytyped.<outputPackage>`, `minimize = Selection.None`.

## See also

- [ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md)
- [js.async / js.await, JSPI, and the WebAssembly Backend](scala-js-async-jspi-wasm.md)
- [scala-wasm Component Spike](scala-wasm-component-spike.md)
