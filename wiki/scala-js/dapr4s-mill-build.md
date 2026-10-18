# dapr4s on Mill: Scala 3 nightly + Scala.js + ScalablyTyped + scala-wasm

dapr4s moved from sbt to Mill 1.3.0 on 2026-10-13 ([PR #45](https://github.com/sideeffffect/dapr4s/pull/45)).
History: scala-cli until 2026-10-12, then sbt for one day (see
[ScalablyTyped Facades with Scala CLI](scalablytyped-with-scala-cli.md) and
[js.async / JSPI](scala-js-async-jspi-wasm.md) for the sbt notes). This article records what was needed and why.

## Parity results (verified against sbt on the same commit)

- `dapr4s_3` / `dapr4s_sjs1_3` POMs: byte-identical to sbt `makePom` (version aside), so identical to the
  published 3.0.0 POMs. Jar entry sets identical (804 / 13330 entries, facade `.sjsir` included).
- ScalablyTyped facade sources: byte-identical (3559 files).
- wasm POMs: overlay, linker and `sbt-dapr4s-wasm` identical; `dapr4s-wasm` / glue / glue-exports differ only by
  dropped test-scope dependencies and dependency order. Exclusions are kept on every dependency.
- All test legs green: JVM/JS unit + integration, wall unit tests, e2e in both core modes, sbt consumer check.

## Mill facts learned

- **Scala 3.8+ on the JVM**: Mill depends on `org.scala-lang:scala-library:3.x` directly; sbt on
  `scala3-library_3` (which brings the same `scala-library`). Override `scalaLibraryMvnDeps` to keep sbt's
  POM coordinate.
- **Mill's POM renderer** omits `<organization>`, writes an empty `<email>` and orders elements differently. For
  byte-identical POMs, override `pom` with your own renderer (`Dapr4sPom` in `buildsupport.mill`).
- **PublishModule** demands that every `moduleDep` is a `PublishModule`. To keep a never-published module out of
  the POM (the facades, the wasm stubs), make it a `PublishModule` and filter it out of BOTH `publishXmlDeps`
  (POM) and `publishMvnDeps` (ivy.xml).
- **Scala.js test link bug (Scala >= 3.8)**: `TestScalaJSModule.scalaJSTestDeps` resolves the test bridge
  transitively and PREPENDS it to the link classpath, which brings `scalajs-scalalib_2.13:2.13.x+1.22.0`. The 2.13
  stdlib IR then shadows the Scala 3 one (first IR file wins): "Referring to non-existent method
  scala.Some.orNull()". Plain-JS unit tests may not reach such code; the Wasm integration link did. Fix: exclude
  `org.scala-js:scalajs-scalalib_2.13` from `scalaJSTestDeps`.
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
- **JDK**: the build header `//| mill-jvm-version: zulu:25.0.3` makes Mill run on and fork tests with Zulu 25;
  `//| mill-jvm-opts` sets the daemon heap.
- **scalafmt**: `./mill mill.scalalib.scalafmt.ScalafmtModule/scalafmt [--check]` runs the scalafmt CLI in the
  workspace root (it honours `project.excludeFilters`). Add `project.git = true` or it formats `out/`.

## ScalablyTyped without an sbt plugin

`mill-scalablytyped` 0.4.1 (Mill 1) hard-codes stdlib `es6` (open:
[lolgab/mill-scalablytyped#51](https://github.com/lolgab/mill-scalablytyped/pull/51)). The sbt GenSource plugin's
generator, `org.scalablytyped.converter.internal.ImportTypingsGenSources`, lives in the
`sbt-converter_2.12_1.0` jar but uses no sbt API. A 2.12 module depending on `importer-portable_2.12` + that jar
(intransitive) can call it with the sbt plugin's defaults and get the same output (`build-tools/st-gen`).
Options to reproduce sbt: `enableScalaJsDefined = Selection.All` for Scala 3, `ignored = {typescript}`,
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
    (Mill 1.3.0: Scala 3.9.0). 1.x plugins work on the same and newer 1.x Mill, not older.
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
  - Dynver on a dirty tree has a minute timestamp: two Mill runs can compute different versions. A script that
    publishes and then consumes must read the version from what was published.
- Upstream Mill work that retires the workarounds: [#7783](https://github.com/com-lihaoyi/mill/pull/7783) (merged 2026-10-17, unreleased)
  (organization + WasmComponent), [#7784](https://github.com/com-lihaoyi/mill/pull/7784) (wit-bindgen-scala),
  [#7785](https://github.com/com-lihaoyi/mill/pull/7785) (Wasmtime JS env).
  In the plugin: #7783 → `ScalaWasmModule` + the link driver, #7784 → `WitBindgenModule`.

## On unreleased upstream (2026-10-16, branch build/mill-upstream-snapshot)

The build runs on Mill `1.3.0-20-56dcdf` (main at the merge of [mill#7783](https://github.com/com-lihaoyi/mill/pull/7783), which
already contains [mill#7786](https://github.com/com-lihaoyi/mill/pull/7786)) and mill-scalablytyped `0.4.1-9-393207`
(`main`, which has [#50](https://github.com/lolgab/mill-scalablytyped/pull/50) merged, + [#51](https://github.com/lolgab/mill-scalablytyped/pull/51)
+ [#52](https://github.com/lolgab/mill-scalablytyped/pull/52)), both built by `scripts/upstream-snapshot.sh`. Facts:

- **Using a locally built Mill**: `CI=true ./mill dist.installLocal --binFile <file>` publishes ~110 modules to
  `~/.ivy2/local` and writes an assembly. `CI=true` drops the dirty hash, so the version (`<tag>-<n>-<sha6>`) is the
  same on every machine with tags fetched. Copy the assembly to `~/.cache/mill/download/<version>` and write
  `//| mill-version: <version>-jvm`: the launcher then uses it and never downloads.
- **What retired**: `Scala3JsTests` (mill#7786); `build-tools/st-gen` + `npmInstall` (mill-scalablytyped
  `scalablyTypedStdLibs`, `scalablyTypedNpmCi`, `scalablyTypedOutputPackage`; the facade jar entries are identical
  to 3.0.0's, 3559 sources); `ScalaWasmModule`'s per-task overrides (now `scalaJSOrganization`); the component link
  driver `WasmLink` (Mill's `fastLinkJS` with `ModuleKind.WasmComponent`).
- **Gotcha**: a module that was a `TestScalaJSModule` only through a removed workaround trait silently becomes a
  JVM test module: `testForked` then runs munit's JS runner on the JVM ("A method defined in a native JavaScript type
  ... has been called"). Mix `TestScalaJSModule` in explicitly.
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
