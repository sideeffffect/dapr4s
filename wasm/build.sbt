// =================================================================================================
// dapr4s `wasm` platform — prototype (docs/WASM-COMPONENTS-PROPOSAL.md §6)
//
// A dapr4s app built as a WASI component for dapr-wasm-components 0.7.5 with the scala-wasm fork of
// Scala.js (`ModuleKind.WasmComponent`).
//
// WHY a separate (nested) sbt build and not projects in ../build.sbt: the scala-wasm sbt plugin
// (`io.github.scala-wasm:sbt-scalajs`) is a FORK of `org.scala-js:sbt-scalajs` with the same classes
// and keys. One meta-build cannot load both, and switching the root build to the fork would change
// coreJS's compiler/linker/library and so its published artifact and POM. A nested build keeps the
// published build byte-for-byte untouched AND makes the wasm target opt-in: `sbt test` at the repo
// root never sees it. Run it with `cd wasm && sbt ...` (see AGENTS.md "Wasm component layer rules").
//
// THE ARTIFACTS (all com.github.sideeffffect, at core's sbt-dynver version, normal suffixes):
//
//   dapr4s-wasm_sjs1_3                 wallWasm: the Scala 3 adapters (the wall: StateCapability over
//                                      the WIT imports, error mapping, invoke dispatcher, the real
//                                      AppRoot) + DaprComponent + the inert callbacks (wasmApi and
//                                      wallInert are merged into its jar). Depends on dapr4s core
//                                      (dapr4s_sjs1_3) and dapr4s-wasm-glue.
//   dapr4s-wasm-glue_sjs1_2.13         witGlue: the wit-bindgen-scala bindings of WIT world `app` +
//                                      the hand-written 2.13 import traits/objects and export traits
//   dapr4s-wasm-glue-exports_sjs1_2.13 witExports: the hand-written `@WitExport` trampolines. A
//                                      COMPONENT's dependency only (the plugin adds it)
//   dapr4s-wasm-scalalib-overlay       scalalibOverlay: the fork-patched stdlib .sjsir (no suffix:
//                                      not Scala code, an IR overlay)
//   sbt-dapr4s-wasm (sbt 1, 2.12)      sbtDapr4sWasm: the build logic (exclusions, overlay,
//                                      wkg wit fetch, dapr4sComponent)
//
// Never published: the link-time STUBS (witRootStub, componentRootStub; `Provided`, so they never
// reach a consumer's classpath — on a linker classpath they would shadow the real `AppRoot` / the
// app's `ComponentRoot`, first IR file per class wins), coreWasm (source mode only) and the test
// components.
//
// THE TWO-COMPILER CONSTRAINT: mainline Scala 3 (every nightly included) has no WIT codegen. WIT
// imports are lowered at the CALL SITE by the compiler backend and `@WitExport` is
// compiler-emitted, so every import call and every export must be compiled by Scala 2.13 +
// scala-wasm's compiler plugin. That 2.13 code is kept small and hand-written (src/wasm/glue*):
// Scala 3 code calls `witglue.StateReadImports.get(...)` (whose body the 2.13 compiler lowered) and
// implements plain export traits.
// =================================================================================================

import dapr4s.sbtwasm.Dapr4sWasm

// The repository root: this build lives in <repo>/wasm.
val repoDir = file("..").getAbsoluteFile.getCanonicalFile
val wasmDir = file(".").getAbsoluteFile.getCanonicalFile
val witDir = wasmDir / "wit"

// Single source of truth: the root build. A nightly bump there applies here on the next load.
def rootBuildVal(name: String): String = {
  val src = IO.read(repoDir / "build.sbt")
  s"""val $name = "([^"]+)"""".r
    .findFirstMatchIn(src)
    .getOrElse(sys.error(s"../build.sbt defines no string val named $name"))
    .group(1)
}
val scala3Nightly = rootBuildVal("scala3Nightly")
val scalaJavaTime = rootBuildVal("scalaJavaTime")
val munitVersion = rootBuildVal("munit")

// Keep in sync with `compilerFlags` in ../build.sbt.
lazy val compilerFlags = Seq(
  "-language:experimental.captureChecking",
  "-language:experimental.pureFunctions",
  "-Ycc-verbose",
  "-Yexplicit-nulls",
  "-experimental",
  "-Wconf:any:error",
)

// The WIT-aware compiler: Scala 2.13 + scala-wasm's `scalajs-compiler` plugin (the officially
// supported path). scala-wasm's only Scala 3 build (scala3-compiler 3.8.3-RC1-wasm.4) is stale
// against library 1.22.1-wasm.6 / wit-bindgen-scala 0.1.0 and rejects the generated bindings
// (96x "not compatible with the Component Model"); see the wiki article
// wiki/scala-js/scala-wasm-component-spike.md, blocker 2.
val scala2Wit = "2.13.18"

// The scala-wasm sbt plugin this build runs on (project/plugins.sbt), which the published sbt-dapr4s-wasm plugin
// depends on. Read from plugins.sbt so the two cannot drift.
val scalaWasmSbtPluginVersion =
  """"io.github.scala-wasm" % "sbt-scalajs" % "([^"]+)"""".r
    .findFirstMatchIn(IO.read(wasmDir / "project" / "plugins.sbt"))
    .getOrElse(sys.error("project/plugins.sbt adds no io.github.scala-wasm:sbt-scalajs"))
    .group(1)

ThisBuild / scalaVersion := scala3Nightly
// POM metadata: the same as core's (../build.sbt). The version is sbt-dynver's (via sbt-ci-release),
// computed from the same git as core's, so one commit gives core and the wasm artifacts one version.
ThisBuild / organization := "com.github.sideeffffect"
ThisBuild / organizationName := "com.github.sideeffffect"
ThisBuild / organizationHomepage := Some(url("https://github.com/sideeffffect/dapr4s/"))
ThisBuild / homepage := Some(url("https://github.com/sideeffffect/dapr4s/"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://spdx.org/licenses/Apache-2.0.html"))
ThisBuild / developers := List(
  Developer("sideeffffect", "Ondra Pelech", "", url("https://github.com/sideeffffect/")),
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/sideeffffect/dapr4s.git"),
    "scm:git:github.com/sideeffffect/dapr4s.git",
    Some("scm:git:git@github.com:sideeffffect/dapr4s.git"),
  ),
)
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies"
// Same formatting rules as the root build (src/wasm and test/wasm are part of the same tree).
ThisBuild / scalafmtConfig := repoDir / ".scalafmt.conf"

// -------------------------------------------------------------------------------------------------
// dapr4s core on the wasm platform: the PUBLISHED dapr4s_sjs1_3 (default) or SOURCE.
//
// Default: the wall depends on the published Scala.js artifact `dapr4s_sjs1_3:<coreVersion>` (Scala.js
// IR), which the scala-wasm linker links into the component. This works because the wasm platform
// traits (src/wasm/core: DaprCapabilityPlatform, ForwardersPlatform) are identical to the Scala.js
// ones (all empty), so dapr4s_sjs1_3 IS "shared + empty platform traits" plus the JS wall, which
// nothing on the wasm path reaches (the linker drops it; its mainline org.scala-js library/javalib are
// excluded by Dapr4sWasmPlugin). `<coreVersion>` is the latest core release (CoreRelease below) or
// `-Ddapr4s.wasm.core=<version>`; a release publishes the wasm artifacts against the core of the SAME
// version (the release workflow passes it).
//
// `-Ddapr4s.wasm.core=source`: coreWasm compiles ../src/shared + ../src/wasm/core instead, so the unit
// tests and the e2e cover the shared code under change (CI runs both modes). Nothing is publishable
// in this mode (the wall's POM would name the unpublished coreWasm).
// -------------------------------------------------------------------------------------------------
val CoreRelease = "3.0.0"
val coreMode: String = sys.props.get("dapr4s.wasm.core").filter(_.nonEmpty).getOrElse(CoreRelease)
val coreFromSource: Boolean = coreMode == "source"

// -------------------------------------------------------------------------------------------------
// Publishing. `publishLocal` always works (release mode). Central publishing (sbt-ci-release, the
// `publish` job of ../.github/workflows/ci.yml, which sets PUBLISH_WASM_TO_CENTRAL=true) needs that
// variable in the environment, so a local `sbt publish` here can never reach Central by accident.
// -------------------------------------------------------------------------------------------------
val publishToCentral: Boolean = sys.env.get("PUBLISH_WASM_TO_CENTRAL").contains("true")

lazy val noPublish = Seq(publish / skip := true, publishLocal / skip := true, publishArtifact := false)
lazy val published = Seq(
  publish / skip := !publishToCentral || coreFromSource,
  publishLocal / skip := coreFromSource,
  // Central needs a javadoc jar; scaladoc of the nightly/CC code is slow, so only build it to publish.
  Compile / packageDoc / publishArtifact := publishToCentral,
  // Test docs are never published (and the wall's would hit the same scaladoc crash as its main docs).
  Test / packageDoc / publishArtifact := false,
)

// -------------------------------------------------------------------------------------------------
// WIT dependencies come from OCI via wkg, never from git: `wit/world.wit` includes
// `dapr-wasm-components:interfaces/app@0.7.5`, `wkg-config.toml` is the upstream file that maps the
// namespace to ghcr.io, and `wkg wit fetch` writes wit/deps/ (git-ignored) + wkg.lock (committed,
// pins the package digest). Runs before wit-bindgen-scala. Only this build needs the WIT (to
// generate dapr4s-wasm-glue's bindings); apps and components do not.
// -------------------------------------------------------------------------------------------------
lazy val witFetch = taskKey[Unit]("Fetch WIT dependencies with `wkg wit fetch` (OCI)")
ThisBuild / witFetch := Dapr4sWasm.witFetch(witDir, streams.value.log)

// Settings of every project compiled by dapr4s's nightly with dapr4s's flags.
// (The dependency exclusions come from the Dapr4sWasmPlugin, enabled on each of these projects.)
lazy val nightly = Seq(
  scalaVersion := scala3Nightly,
  scalacOptions ++= compilerFlags,
)

lazy val witAware = Seq(scalaVersion := scala2Wit)

// Sources live in the repo tree; a project owns only its target directory.
def sourcesIn(dirs: File*) = Seq(
  Compile / unmanagedSourceDirectories := dirs,
  Test / unmanagedSourceDirectories := Nil,
  Compile / unmanagedResourceDirectories := Nil,
  Test / unmanagedResourceDirectories := Nil,
)

// Source mode only: dapr4s's shared code compiled for the wasm platform.
lazy val coreWasm = project
  .in(file("core"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "shared", repoDir / "src" / "wasm" / "core"))
  .settings(
    name := "dapr4s-wasm-core",
    // java.time on the Scala.js-family platforms (part of the shared public API). Only
    // Duration-style arithmetic links without JS; formatting/parsing reaches scala-java-locales'
    // `window` (wiki: "What dapr4s's shared code needs on a no-JS target").
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,
  )

def withCore(p: Project): Project =
  if (coreFromSource) p.dependsOn(coreWasm)
  else p.settings(libraryDependencies += "com.github.sideeffffect" %%% "dapr4s" % coreMode)

// -------------------------------------------------------------------------------------------------
// The 2.13 side, compiled by the WIT-aware compiler (Scala 2.13 + scala-wasm's plugin): the
// wit-bindgen-scala BINDINGS (generated by the plugin) + a small HAND-WRITTEN glue (src/wasm/glue*)
// covering only what the Scala 3 wall uses. See src/wasm/glue/WitGlue.scala for why it must be 2.13.
// -------------------------------------------------------------------------------------------------

// WHAT: rewrites the generated bindings in place so that every `use`d WIT type ALIAS points at its
// defining interface: in `invocation_callback`, `type Metadata = Array[wit.Tuple2[String, String]]`
// becomes `type Metadata = dapr4swit.dapr_wasm_components.interfaces.types.Metadata`.
// WHY: wit-bindgen-scala 0.1.0 renders a `use` of a type alias structurally (its `render_type` writes
// named list/option/result/tuple typedefs out), so the `@WitAlias` of `types.metadata` is lost and the
// linker's component-type has no `metadata` type export in the exported `*-callback` instances;
// `wac compose` with the dapr-wasm-components inbound provider then fails ("instance is missing
// expected list export `metadata`"). The scala-wasm compiler keeps an alias reached through a plain
// type alias since https://github.com/scala-wasm/scala-wasm/pull/269 — but only in this form.
// WHY SAFE: a rewrite replaces a type with an alias of the SAME structural type (both sides are
// checked equal before rewriting), so no Scala type changes; only the alias the compiler sees does.
// It applies only to an un-annotated `type X = R` whose name has exactly one `@WitAlias` definition
// `type X = R` (same R) elsewhere in the bindings, i.e. a `use`. Idempotent: a rewritten line no longer
// matches. Verified by scripts/wasm/e2e.sh, which composes the linker's component AS IS.
// RETIRES when wit-bindgen-scala renders `use`d aliases as references to the defining interface.
def useWitAliases(files: Seq[File]): Seq[File] = {
  val TypeLine = """^  type (\w+) = (.+)$""".r
  def objectOf(src: String): String = {
    val pkg = """(?m)^package ([\w.]+)$""".r.findFirstMatchIn(src).map(_.group(1))
    val obj = """(?m)^package object (\w+)""".r.findFirstMatchIn(src).map(_.group(1))
    (pkg.toList ++ obj.toList).mkString(".")
  }
  // name -> (FQN of the defining package object, right-hand side), for every @WitAlias definition.
  val defs = files.flatMap { f =>
    val lines = IO.readLines(f)
    lines.indices.collect {
      case i if i > 0 && lines(i - 1).contains("@scala.scalajs.wit.annotation.WitAlias(") =>
        lines(i) match { case TypeLine(n, rhs) => Some((n, (objectOf(IO.read(f)), rhs))); case _ => None }
    }.flatten
  }
  val unique = defs.groupBy(_._1).collect { case (n, Seq((_, d))) => n -> d }
  files.foreach { f =>
    val lines = IO.readLines(f)
    val rewritten = lines.indices.map { i =>
      val annotated = i > 0 && lines(i - 1).contains("@scala.scalajs.wit.annotation.WitAlias(")
      lines(i) match {
        case TypeLine(n, rhs) if !annotated && unique.get(n).exists(_._2 == rhs) =>
          s"  type $n = ${unique(n)._1}.$n"
        case l => l
      }
    }
    if (rewritten != lines) IO.writeLines(f, rewritten)
  }
  files
}

// The bindings of world `app` under `dapr4swit` (a dapr4s-specific root package, like `dapr4styped` for
// the ScalablyTyped facades, so a consumer's own wit-bindgen output cannot collide with it) + the
// hand-written import traits/objects and the 7 `*CallbackExports` traits. Scala 3 uses the binding types
// directly; it cannot call their `@WitImport`s. The glue tracks the WIT version (world.wit).
lazy val witGlue = project
  .in(file("wit-glue"))
  .enablePlugins(ScalaJSPlugin)
  .settings(published, witAware, sourcesIn(repoDir / "src" / "wasm" / "glue"))
  .settings(
    name := "dapr4s-wasm-glue",
    scalaJSWitDirectory := witDir,
    scalaJSWitWorld := Some("app"),
    scalaJSWitPackage := Some("dapr4swit"),
    // The generator must wait for wit/deps/ (sbt runs witFetch once per command). useWitAliases: see
    // its definition. The plugin also runs the generator for Test, into the SAME directory (it writes to
    // `Compile / sourceManaged` in both configurations): the two runs in parallel corrupted each other's
    // files (and the rewrite's). This project has no tests and Test sees the main classes anyway, so Test
    // generates nothing.
    Compile / scalaJSGenerateWitBindings :=
      useWitAliases((Compile / scalaJSGenerateWitBindings).dependsOn(witFetch).value),
    Test / scalaJSGenerateWitBindings := Nil,
  )

// Hand-written link-time stub of `dapr4s.internal.witglue.AppRoot` (src/wasm/glue-root-stub).
lazy val witRootStub = project
  .in(file("wit-root-stub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(noPublish, witAware, sourcesIn(repoDir / "src" / "wasm" / "glue-root-stub"))
  .settings(name := "dapr4s-wasm-glue-root-stub")
  .dependsOn(witGlue)

// Hand-written `@WitExport` trampolines (src/wasm/glue-exports). Only COMPONENTS depend on it.
lazy val witExports = project
  .in(file("wit-exports"))
  .enablePlugins(ScalaJSPlugin)
  .settings(published, witAware, sourcesIn(repoDir / "src" / "wasm" / "glue-exports"))
  .settings(
    name := "dapr4s-wasm-glue-exports",
    // The stub is a compile-time-only contract: keep it out of the published POM.
    projectDependencies := projectDependencies.value.filterNot(_.name == "dapr4s-wasm-glue-root-stub"),
  )
  .dependsOn(witGlue, witRootStub % Provided)

// -------------------------------------------------------------------------------------------------
// The Scala 3 side (dapr4s's nightly + flags)
// -------------------------------------------------------------------------------------------------

// `dapr4s.wasm.DaprComponent` (src/wasm/api). Its own project only because the ComponentRoot stub
// needs the type and the wall needs the stub: merged into the dapr4s-wasm jar, never published alone.
lazy val wasmApi = withCore(
  project
    .in(file("api"))
    .enablePlugins(Dapr4sWasmPlugin)
    .settings(noPublish, nightly, sourcesIn(repoDir / "src" / "wasm" / "api"))
    .settings(name := "dapr4s-wasm-api"),
)

lazy val componentRootStub = project
  .in(file("component-root-stub"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "component-root-stub"))
  .settings(name := "dapr4s-wasm-component-root-stub")
  .dependsOn(wasmApi)

// The inert implementations of the callback exports the prototype does not dispatch yet. Merged into
// the dapr4s-wasm jar; the test components that bypass the wall compile src/wasm/inert themselves.
lazy val wallInert = project
  .in(file("wall-inert"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(noPublish, nightly)
  .settings(sourcesIn(repoDir / "src" / "wasm" / "inert"))
  .settings(name := "dapr4s-wasm-inert")
  .dependsOn(witGlue)

// The projects whose classes and sources the dapr4s-wasm jars embed, plus the compile-time-only stub: its POM
// leaves them out.
val embeddedInWall = Set("dapr4s-wasm-api", "dapr4s-wasm-inert", "dapr4s-wasm-component-root-stub")

lazy val wallWasm = withCore(
  project
    .in(file("wall"))
    .enablePlugins(Dapr4sWasmPlugin)
    .settings(published, nightly)
    .settings(sourcesIn(repoDir / "src" / "wasm" / "internal"))
    .settings(
      name := "dapr4s-wasm",
      // WHAT: the javadoc jar of dapr4s-wasm is built from NO sources (an empty but valid scaladoc jar).
      // WHY: dotty's scaladoc crashes on this project (ClassCastException Constant -> Tree in
      // Scala2Unpickler.readAnnotArg, reached from MacroAnnotations.isMacroAnnotation) while reading the
      // annotation arguments Scala 2.13 pickled into the wit-bindgen-scala bindings of dapr4s-wasm-glue
      // (@WitImport/@WitExport/@WitScope). Plain compilation never forces those annotations; scaladoc does.
      // WHY SAFE: Central only requires a -javadoc.jar to exist; the -sources.jar still ships every source
      // with its doc comments, and the API a user touches (dapr4s.wasm.DaprComponent) is documented there.
      // RETIRES: when a Scala 3 nightly fixes the unpickler crash, or when the 2.13 glue goes away.
      Compile / doc / sources := Nil,
      projectDependencies := projectDependencies.value.filterNot(m => embeddedInWall(m.name)),
      Compile / packageBin / mappings ++=
        (wasmApi / Compile / packageBin / mappings).value ++ (wallInert / Compile / packageBin / mappings).value,
      Compile / packageSrc / mappings ++=
        (wasmApi / Compile / packageSrc / mappings).value ++ (wallInert / Compile / packageSrc / mappings).value,
      // Unit tests of the wall (test/wasm/unit) against FAKES of the hand-written import traits. They link as
      // plain Scala.js (this project keeps the default JS linker config) and run on Node: no WIT import is
      // reachable from them, so they need neither wasmtime nor a sidecar.
      Test / unmanagedSourceDirectories := Seq(repoDir / "test" / "wasm" / "unit"),
      libraryDependencies ++= Seq(
        "org.scalameta" %%% "munit" % munitVersion % Test,
        // WHAT: the fork's JUnit runtime, added by hand. WHY: munit_sjs1 depends on the UPSTREAM
        // org.scala-js:scalajs-junit-test-runtime_2.13 (it holds org.junit.ComparisonFailure, which munit's
        // assertions throw), and the scala-wasm plugin now excludes every upstream core library
        // (https://github.com/scala-wasm/scala-wasm/pull/271) but adds the fork's JUnit runtime only to
        // projects with its JUnit plugin. Without it the test link fails ("Referring to non-existent class
        // org.junit.ComparisonFailure"). WHY SAFE: the same artifact at the fork's own version, so the
        // classes munit links against are exactly the upstream ones it was built for. RETIRES when the plugin
        // swaps the excluded runtime for the fork's (or munit stops needing it).
        ("io.github.scala-wasm" % "scalajs-junit-test-runtime_2.13" % scalaJSVersion % Test),
      ),
    )
    .dependsOn(wasmApi, wallInert, witGlue, componentRootStub % Provided),
)

// -------------------------------------------------------------------------------------------------
// The build-level artifacts: the stdlib overlay, the sbt plugin
// -------------------------------------------------------------------------------------------------

// The .sjsir of the classes scala-wasm's Scala 3 fork patched in library-js (scala-wasm/scala3@17a0deeb:
// Symbol.scala, ArrayBuilder.scala, Buffer.scala, BoxesRunTime.scala), taken unmodified from its
// published scalalib, + its LICENSE and a NOTICE (scalalib-overlay/). Must be re-checked on every
// nightly bump (a nightly that changes those files would be silently shadowed).
val OverlayVersion = "3.8.3-RC1-wasm.4"
val overlayClassPrefixes = Seq(
  "scala/runtime/BoxesRunTime",
  "scala/Symbol",
  "scala/UniquenessCache",
  "scala/JSUniquenessCache",
  "scala/collection/mutable/ArrayBuilder",
  "scala/collection/mutable/Buffer",
  "scala/collection/mutable/IndexedBuffer",
)
lazy val scalalibOverlay = project
  .in(file("scalalib-overlay"))
  .settings(published)
  .settings(
    name := "dapr4s-wasm-scalalib-overlay",
    crossPaths := false,
    autoScalaLibrary := false,
    Compile / packageBin / mappings := {
      // Retrieved, not a libraryDependency: the published overlay must not depend on the fork's whole scalalib.
      val jars = Dapr4sWasm.retrieve(
        dependencyResolution.value,
        ("io.github.scala-wasm" % "scalajs-scalalib_2.13" % OverlayVersion).intransitive(),
        streams.value.cacheDirectory / "overlay-source",
        streams.value.log,
      )
      val dir = target.value / "overlay"
      IO.delete(dir)
      jars.foreach { jar =>
        IO.unzip(
          jar,
          dir,
          (n: String) => n.endsWith(".sjsir") && overlayClassPrefixes.exists(p => n.startsWith(p)),
        )
      }
      val sjsir = (dir ** "*.sjsir").get.map(f => f -> IO.relativize(dir, f).get)
      require(sjsir.nonEmpty, s"no overlay classes in $jars")
      sjsir ++ Seq(
        baseDirectory.value / "LICENSE" -> "META-INF/LICENSE",
        baseDirectory.value / "NOTICE" -> "META-INF/NOTICE",
      )
    },
    Compile / packageSrc / mappings := Seq(baseDirectory.value / "NOTICE" -> "NOTICE"),
    Compile / packageDoc / mappings := Seq(baseDirectory.value / "NOTICE" -> "NOTICE"),
  )

// The sbt-dapr4s-wasm plugin (an app adds it with addSbtPlugin; it brings the scala-wasm fork of
// sbt-scalajs). Same sources as this build's meta-build (project/plugin-sources.sbt).
lazy val sbtDapr4sWasm = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(published)
  .settings(
    name := "sbt-dapr4s-wasm",
    // Maven-conformant file names (sbt-dapr4s-wasm_2.12_1.0-<v>.jar): the legacy sbt-plugin layout is
    // rejected by the Central Portal's validation. Consumers need sbt >= 1.9 to resolve it.
    sbtPluginPublishLegacyMavenStyle := false,
    // The meta-build's Scala (sbt 1.x plugins are Scala 2.12), not this build's nightly.
    scalaVersion := scala.util.Properties.versionNumberString,
    scalacOptions := Nil,
    addSbtPlugin("io.github.scala-wasm" % "sbt-scalajs" % scalaWasmSbtPluginVersion),
  )

// This build links with its OWN overlay project (the plugin, run from the meta-build, has no published
// version to resolve it by), exactly the artifact an app gets from the plugin.
ThisBuild / dapr4sWasmScalalibOverlay := Seq((scalalibOverlay / Compile / packageBin).value)

// -------------------------------------------------------------------------------------------------
// Test apps: linked as components and run by ../scripts/wasm/e2e.sh against a real daprd. They depend
// on this build's projects directly, hence `dapr4sWasmAddGlueExports := false`.
// -------------------------------------------------------------------------------------------------
lazy val counterRaw = project
  .in(file("counter-raw"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(noPublish, nightly, dapr4sWasmAddGlueExports := false)
  .settings(sourcesIn(repoDir / "test" / "wasm" / "counter-raw", repoDir / "src" / "wasm" / "inert"))
  .dependsOn(witGlue, witExports)

lazy val counter = project
  .in(file("counter"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(noPublish, nightly, dapr4sWasmAddGlueExports := false)
  .settings(sourcesIn(repoDir / "test" / "wasm" / "counter"))
  .dependsOn(wallWasm, witExports)

// Calls the four WIT imports with > 16 flat params (jobs.schedule, conversation.converse,
// actors.register-reminder/-timer): proves the linker lowers them through linear memory
// (https://github.com/scala-wasm/scala-wasm/pull/268; scripts/wasm/e2e.sh).
lazy val importsProbe = project
  .in(file("imports-probe"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(noPublish, nightly, dapr4sWasmAddGlueExports := false)
  .settings(sourcesIn(repoDir / "test" / "wasm" / "imports-probe", repoDir / "src" / "wasm" / "inert"))
  .dependsOn(witGlue, witExports)

lazy val root = project
  .in(file("."))
  .aggregate(
    (if (coreFromSource) Seq[ProjectReference](coreWasm) else Nil) ++ Seq[ProjectReference](
      witGlue,
      witRootStub,
      witExports,
      wasmApi,
      componentRootStub,
      wallInert,
      wallWasm,
      scalalibOverlay,
      sbtDapr4sWasm,
      counterRaw,
      counter,
      importsProbe,
    ): _*,
  )
  .settings(noPublish)
  .settings(name := "dapr4s-wasm-root")
