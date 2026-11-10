# dapr4s on Mill: Scala 3 nightly + Scala.js + ScalablyTyped + scala-wasm

dapr4s moved from sbt to Mill. The migration was first written on top of the Wasm prototype
([PR #45](https://github.com/sideeffffect/dapr4s/pull/45), 2026-10-13, then
[PR #47](https://github.com/sideeffffect/dapr4s/pull/47) for the released upstream versions). Its non-Wasm part was
then ported onto `master` on its own (2026-11-10, [PR #49](https://github.com/sideeffffect/dapr4s/pull/49)), so it can merge before the Wasm prototype
([PR #44](https://github.com/sideeffffect/dapr4s/pull/44)). After #49 merged, #44 took the Mill Wasm modules of
#45/#47 (2026-11-10); the Wasm sections below come from them.
History: scala-cli until 2026-10-12, then sbt (see
[ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md) and
[js.async / JSPI](scala-js-async-jspi-wasm.md) for the sbt notes). This article records what was needed and why.

Versions: Mill **1.3.1** and mill-scalablytyped **0.4.2**, both released, both from Maven Central.

## Parity results (verified against sbt on the same commit)

- `dapr4s_3` / `dapr4s_sjs1_3` POMs: byte-identical to sbt `publishLocal` (version aside), so identical to the
  published POMs. Jar entry sets identical (facade `.sjsir` included).
- ScalablyTyped facade sources: identical to sbt's converter plugin output (3559 files).
- All test legs green: JVM/JS unit + integration.
- wasm POMs (on #45): overlay, linker and `sbt-dapr4s-wasm` identical to sbt's; `dapr4s-wasm` / glue / glue-exports
  differ only by dropped test-scope dependencies and dependency order. Exclusions are kept on every dependency.
  Wall unit tests, e2e in both core modes and the consumer check green.

## Mill facts learned

- **Scala 3.8+ on the JVM**: Mill depends on `org.scala-lang:scala-library:3.x` directly; sbt on
  `scala3-library_3` (which brings the same `scala-library`). Override `scalaLibraryMvnDeps` to keep sbt's
  POM coordinate.
- **Mill's POM renderer** omits `<organization>`, writes an empty `<email>` and orders elements differently. For
  byte-identical POMs, override `pom` with your own renderer (`Dapr4sPom` in `buildsupport.mill`).
- **PublishModule** demands that every `moduleDep` is a `PublishModule`. To keep a never-published module out of
  the POM (the facades, the wasm stubs), make it a `PublishModule` and filter it out of BOTH `publishXmlDeps` (POM) and
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

## scala-wasm (fork of Scala.js) in Mill

- **Organization swap**: override `mandatoryMvnDeps`, `scalacPluginMvnDeps` (2.13: `scalajs-compiler`),
  `scalaJSLinkerClasspath` (Mill's worker + the fork's `scalajs-linker_2.13` and test adapter) and the tests'
  `scalaJSTestDeps`. `scalaJSVersion = "1.22.1-wasm.6"` parses fine (worker "1", suffix `_sjs1`). Mill's worker,
  compiled against mainline Scala.js, links plain JS with the fork's linker without problems.
- **Test modules** (`ScalaJSTests`) do not inherit the outer module's overrides of `mandatoryMvnDeps`: mix the
  swap trait into the test module too.
- **Components**: Mill's linker API has no `ModuleKind.WasmComponent`. A 30-line driver compiled against the
  fork's linker interface (`StandardImpl.linker(config).link(...)`) does it (`wasm/linker/src`, shipped inside
  `dapr4s-wasm-linker` so a consumer resolves linker + driver as one classpath and runs it in a separate JVM).
- **The patched linker without sbt**: compile `linker/shared/src/main/{scala,scala-2}` + `linker/jvm/src/main/scala`
  of the release commit (2.12) against the released `scalajs-ir` / `scalajs-linker-interface`, and take the
  non-class resources (the private library `.sjsir`: `FloatingPointBitsPolyfills`, `RuntimeLong`, ...) from the
  released linker jar. Without them the linker fails "couldn't load FloatingPointBitsPolyfills.sjsir from resources".
- **An sbt plugin built by Mill**: a 2.12 module, sbt as `compileMvnDeps`, a generated `sbt/sbt.autoplugins`
  resource listing the AutoPlugin objects, `artifactSuffix = "_2.12_1.0"`, POM properties `scalaVersion`,
  `sbtVersion` (+ `extraDependencyAttributes`). sbt >= 1.9 resolves it from a Maven repository.
- **A Mill plugin (`mill-dapr4s-wasm`, 2026-10-13)**, the twin of the sbt plugin, with all of the above as traits
  (`ScalaWasmModule`, `Dapr4sWasmModule`, `Dapr4sWasmTests`, `WitModule`, `WitBindgenModule`,
  `Dapr4sWasmComponentModule`). Facts:
  - A Mill 1.x plugin is a Scala 3 library against `com.lihaoyi::mill-libs:<mill version>` with
    `platformSuffix = "_mill" + mill.util.BuildInfo.millBinPlatform` → `<name>_mill1_3`; a consumer loads it with
    `//| mvnDeps: ["org::name::version"]` in the `build.mill` header. Compile it with `mill.util.BuildInfo.scalaVersion`
    (Mill 1.3.0 and 1.3.1: Scala 3.9.0). 1.x plugins work on the same and newer 1.x Mill, not older.
  - Plain `.scala` files in `mill-build/src/` are compiled into the meta-build with no `mill-build/build.mill`
    (and the root header keeps working), so the plugin's sources double as dapr4s's own build logic (like sbt's
    `project/plugin-sources.sbt` trick).
  - A local Maven repo for the plugin: `//| repositories: ["file:///..."]` in the header (meta-build) plus
    `def repositories = Seq("file:///...")` on the modules (app dependencies).
  - **Do not resolve build TOOLS with a ScalaModule's `defaultResolver()`**: it forces `scala-library` to the
    module's Scala version (`actualResolutionParamsOverride`), so the 2.12 linker got the Scala 3 library
    (`NoClassDefFoundError: scala/Serializable`). Use `Lib.resolveDependencies(repositoriesTask(), ...)`.
  - Task.Sources with relative paths in a trait (`Task.Sources("wit/world.wit", ...)`) resolve against each
    module's `moduleDir`: good defaults for consumers, overridable.
  - A script that publishes and then consumes must read the version from what was published (dynver's minute
    timestamp on a dirty tree, see above).
- Upstream Mill work that retires the workarounds: [#7783](https://github.com/com-lihaoyi/mill/pull/7783) (merged 2026-10-17, released in Mill 1.3.1 on 2026-10-19)
  (organization + WasmComponent), [#7784](https://github.com/com-lihaoyi/mill/pull/7784) (wit-bindgen-scala),
  [#7785](https://github.com/com-lihaoyi/mill/pull/7785) (Wasmtime JS env).
  In the plugin: #7783 → `ScalaWasmModule` + the link driver, #7784 → `WitBindgenModule`.

### Wasm on released Mill 1.3.1

- **What retired with Mill 1.3.1** (Wasm part): `ScalaWasmModule`'s per-task overrides (now
  `scalaJSOrganization`) and the component link driver `WasmLink` (Mill's `fastLinkJS` with
  `ModuleKind.WasmComponent`, from [mill#7783](https://github.com/com-lihaoyi/mill/pull/7783)).
- **Mill's Scala.js worker is Scala 3 and loads a `_2.13` linker**; sbt loads `_2.12`. The patched linker is now
  published for both (`dapr4s-wasm-linker_2.12` / `_2.13`). The 2.13 build needs
  `scala-parallel-collections` 1.2.0 (`ParCollOps`), as scala-wasm's own build adds it.
- **dapr-wasm-components OCI rename (2026-10-15)**: the flat `ghcr.io/sideeffffect/dapr-wasm-components-<module>`
  packages are deleted; modules live under `ghcr.io/sideeffffect/dapr-wasm-components/<module>`. Upstream
  `compose.sh` up to v0.7.4 still pulled the flat names, so dapr4s pulled the providers itself and passed
  `--outbound` / `--inbound`; v0.7.5's `compose.sh` uses the new names, so dapr4s calls it with `--tag 0.7.5` again.

## See also

- [scala-wasm Component Spike](scala-wasm-component-spike.md)
- [ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md)
- [js.async / js.await, JSPI, and the WebAssembly Backend](scala-js-async-jspi-wasm.md)
