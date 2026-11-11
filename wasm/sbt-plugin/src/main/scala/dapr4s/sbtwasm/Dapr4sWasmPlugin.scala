package dapr4s.sbtwasm

import sbt._
import sbt.Keys._

import org.scalajs.linker.interface.{ESVersion, ModuleKind}
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

/** The build logic a dapr4s `wasm` app needs, as an sbt plugin (`sbt-dapr4s-wasm`).
  *
  * WHY a plugin and not a library: everything here is a workaround in the BUILD, not in code — which linker links the
  * app, which stdlib IR wins on the linker classpath, which transitive libraries must not reach the classpath, and how
  * the linked module becomes a component typed by the real WIT world. A library cannot change any of that. dapr4s's own
  * `wasm/` build compiles this very file into its meta-build (`wasm/project/plugin-sources.sbt`) and points the two
  * artifact-backed keys (`dapr4sWasmLinkerClasspath`, `dapr4sWasmScalalibOverlay`) at its own projects, so the test
  * components in dapr4s and an external app (dapr4s-examples) build the same way.
  *
  * Two plugins, both opt-in (`enablePlugins`), on top of the scala-wasm FORK of sbt-scalajs
  * (`io.github.scala-wasm:sbt-scalajs`), which this plugin depends on:
  *
  *   - [[Dapr4sWasmPlugin]]: every project compiled by the Scala 3 nightly against dapr4s's wasm artifacts (library or
  *     app): dependency exclusions + the patched linker (`dapr4s-wasm-linker`).
  *   - [[Dapr4sWasmComponentPlugin]]: a project linked as a WASI component (an app): WasmComponent link, the stdlib
  *     overlay (`dapr4s-wasm-scalalib-overlay`), the `@WitExport` trampolines (`dapr4s-wasm-glue-exports`),
  *     `wkg wit fetch` and `dapr4sComponent` / `dapr4sComponentFull`.
  *
  * An app's whole setup:
  * {{{
  * // project/plugins.sbt
  * addSbtPlugin("com.github.sideeffffect" % "sbt-dapr4s-wasm" % "<version>")
  * // build.sbt (+ wit/world.wit, wkg-config.toml, wkg.lock next to it)
  * lazy val app = project.enablePlugins(Dapr4sWasmComponentPlugin)
  *   .settings(libraryDependencies += "com.github.sideeffffect" %%% "dapr4s-wasm" % dapr4sWasmVersion)
  * // + `object dapr4s.wasm.ComponentRoot { def component: DaprComponent = MyComponent }`
  * }}}
  *
  * Each workaround retires on its own when scala-wasm ships the fix: see dapr4s AGENTS.md "Wasm component layer rules".
  */
object Dapr4sWasmPlugin extends AutoPlugin {
  override def requires: Plugins = ScalaJSPlugin
  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    /** The version of dapr4s's wasm artifacts this plugin was published with (they are published together, from the
      * same dapr4s commit, at the same version). Read from the plugin jar's manifest; inside dapr4s's own build
      * (sources compiled into the meta-build, no jar) there is none and this fails — that build never asks for it.
      */
    lazy val dapr4sWasmVersion: String =
      Option(Dapr4sWasmPlugin.getClass.getPackage)
        .flatMap(p => Option(p.getImplementationVersion))
        .getOrElse(
          sys.error("dapr4sWasmVersion: no Implementation-Version (not running from the published plugin jar)"),
        )

    val dapr4sWasmLinkerClasspath =
      taskKey[Seq[File]]("Classpath of the linker that links the app: dapr4s-wasm-linker_2.12 + its dependencies")
  }
  import autoImport._

  // -----------------------------------------------------------------------------------------------
  // Upstream blocker (scala-wasm linker: WIT params via linear memory, > 16 flat params): the export
  // side is a `???`, the import side emits nothing. Link with dapr4s-wasm-linker_2.12, the
  // 1.22.1-wasm.6 linker rebuilt with dapr4s's patch (scripts/wasm/patches/), republished under
  // dapr4s's coordinates with scala-wasm's LICENSE/NOTICE. Only the LINKER is replaced; the plugin,
  // library, javalib and compiler plugin stay the released ones. RETIRES when a scala-wasm release
  // passes params via memory: drop this override.
  // -----------------------------------------------------------------------------------------------
  override def globalSettings: Seq[Setting[_]] = Seq(
    dapr4sWasmLinkerClasspath := {
      val s = streams.value
      Dapr4sWasm.retrieve(
        (scalaJSLinkerImpl / dependencyResolution).value,
        Dapr4sWasm.Org % "dapr4s-wasm-linker_2.12" % dapr4sWasmVersion,
        s.cacheDirectory / "dapr4s-wasm-linker",
        s.log,
      )
    },
    scalaJSLinkerImpl / fullClasspath := dapr4sWasmLinkerClasspath.value.map(Attributed.blank(_)),
  )

  // -----------------------------------------------------------------------------------------------
  // A nightly-compiled project that depends on 2.13-compiled artifacts (dapr4s's WIT glue) must NOT
  // see the 2.13 stdlib the scala-wasm plugin adds to those (different organization, so no eviction
  // against the nightly's): drop it. dapr4s core (dapr4s_sjs1_3) and other cross-built dependencies
  // (scala-java-time, upickle, ...) bring MAINLINE Scala.js (scalajs-library -> scalajs-javalib).
  // Different organization than the fork's, so no eviction either: both would be on the classpath and
  // the first IR file per class would win. Keep only the fork's. RETIRES when the fork's library and
  // mainline's can evict each other (same coordinates) or mainline Scala.js gains the component target.
  // -----------------------------------------------------------------------------------------------
  override def projectSettings: Seq[Setting[_]] = Seq(
    excludeDependencies ++= Dapr4sWasm.exclusions,
  )
}

/** A dapr4s app linked as a WASI component for dapr-wasm-components (`ModuleKind.WasmComponent`). */
object Dapr4sWasmComponentPlugin extends AutoPlugin {
  override def requires: Plugins = Dapr4sWasmPlugin
  override def trigger: PluginTrigger = noTrigger

  object autoImport {
    val dapr4sWasmWitDirectory =
      settingKey[File]("WIT directory: world.wit (`include dapr-wasm-components:interfaces/app@...`) + deps/ from wkg")
    val dapr4sWasmWorld = settingKey[String]("WIT world the component targets (default: app)")
    val dapr4sWasmComponentsDirectory = settingKey[File]("Where dapr4sComponent writes <project name>.wasm")
    val dapr4sWasmWitFetch =
      taskKey[Unit]("`wkg wit fetch --config wkg-config.toml` next to the WIT directory (WIT only through OCI)")
    val dapr4sWasmScalalibOverlay =
      taskKey[Seq[File]]("The fork-patched stdlib .sjsir (dapr4s-wasm-scalalib-overlay), first on the linker classpath")
    val dapr4sWasmAddGlueExports =
      settingKey[Boolean]("Add dapr4s-wasm-glue-exports (the @WitExport trampolines) to libraryDependencies")
    val dapr4sComponent = taskKey[File]("Linked app (fastLinkJS) as a WASI component typed by the real WIT world")
    val dapr4sComponentFull = taskKey[File]("Like dapr4sComponent, from fullLinkJS (optimized)")
  }
  import autoImport._
  import Dapr4sWasmPlugin.autoImport.dapr4sWasmVersion

  override def buildSettings: Seq[Setting[_]] = Seq(
    dapr4sWasmWitDirectory := (LocalRootProject / baseDirectory).value / "wit",
    dapr4sWasmWitFetch := Dapr4sWasm.witFetch(dapr4sWasmWitDirectory.value, streams.value.log),
    // -----------------------------------------------------------------------------------------------
    // Upstream blocker (the nightly's Scala.js stdlib, org.scala-js:scalajs-scalalib_2.13:<nightly>,
    // still uses JS interop: BoxesRunTime.equals uses JS `===`, reachable from any program; Symbol's
    // UniquenessCache is a js.Dictionary; ...). scala-wasm's Scala 3 fork patched exactly those
    // library-js files; dapr4s-wasm-scalalib-overlay carries their .sjsir and goes FIRST on the linker
    // classpath (the Scala.js IR loader keeps the first IR file per class name). Resolved with
    // `retrieve` through the linker's global resolution (not libraryDependencies, whose coursier
    // resolution applies a project's excludeDependencies). RETIRES when the nightly's library-js is
    // Wasm-clean: drop the overlay.
    // -----------------------------------------------------------------------------------------------
    dapr4sWasmScalalibOverlay := {
      val s = streams.value
      Dapr4sWasm.retrieve(
        (Global / scalaJSLinkerImpl / dependencyResolution).value,
        (Dapr4sWasm.Org % "dapr4s-wasm-scalalib-overlay" % dapr4sWasmVersion).intransitive(),
        s.cacheDirectory / "dapr4s-wasm-scalalib-overlay",
        s.log,
      )
    },
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    dapr4sWasmWorld := "app",
    dapr4sWasmComponentsDirectory := (LocalRootProject / target).value / "components",
    dapr4sWasmAddGlueExports := true,
    // The trampolines are a COMPONENT's dependency only: every `@WitExport` is a linker entry point,
    // which a plain-JS link (unit tests of a library) rejects. Hence not a dependency of dapr4s-wasm.
    libraryDependencies ++= {
      if (dapr4sWasmAddGlueExports.value)
        Seq(
          (Dapr4sWasm.Org % "dapr4s-wasm-glue-exports" % dapr4sWasmVersion).cross(
            CrossVersion.for3Use2_13With("sjs1_", ""),
          ),
        )
      else Nil
    },
    Compile / scalaJSLinkerConfig := {
      (Compile / scalaJSLinkerConfig).value
        .withESFeatures(_.withUseWebAssembly(true).withESVersion(ESVersion.ES2022))
        .withModuleKind(ModuleKind.WasmComponent)
    },
    Compile / fullClasspath :=
      dapr4sWasmScalalibOverlay.value.map(Attributed.blank(_)) ++ (Compile / fullClasspath).value,
    dapr4sComponent := {
      dapr4sWasmWitFetch.value
      (Compile / fastLinkJS).value
      Dapr4sWasm.buildComponent(
        streams.value.log,
        (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm",
        target.value / "component-fast",
        dapr4sWasmWitDirectory.value,
        dapr4sWasmWorld.value,
        dapr4sWasmComponentsDirectory.value / s"${name.value}.wasm",
      )
    },
    dapr4sComponentFull := {
      dapr4sWasmWitFetch.value
      (Compile / fullLinkJS).value
      Dapr4sWasm.buildComponent(
        streams.value.log,
        (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm",
        target.value / "component-full",
        dapr4sWasmWitDirectory.value,
        dapr4sWasmWorld.value,
        dapr4sWasmComponentsDirectory.value / s"${name.value}-opt.wasm",
      )
    },
  )
}

object Dapr4sWasm {
  val Org = "com.github.sideeffffect"
  val ScalaWasmOrg = "io.github.scala-wasm"

  val exclusions: Seq[ExclusionRule] = Seq(
    ExclusionRule(ScalaWasmOrg, "scala-library"),
    ExclusionRule(ScalaWasmOrg, "scala3-library_3"),
    ExclusionRule(ScalaWasmOrg, "scala3-library_sjs1_3"),
    ExclusionRule(ScalaWasmOrg, "scalajs-scalalib_2.13"),
    ExclusionRule("org.scala-js", "scalajs-library_2.13"),
    ExclusionRule("org.scala-js", "scalajs-javalib"),
  )

  def retrieve(lm: sbt.librarymanagement.DependencyResolution, module: ModuleID, dir: File, log: Logger): Seq[File] = {
    val files = lm
      .retrieve(module, scalaModuleInfo = None, dir, log)
      .fold(
        w =>
          sys.error(
            s"sbt-dapr4s-wasm: cannot resolve $module (${w.resolveException.getMessage}). If dapr4s's wasm artifacts " +
              "of this version are not on Maven Central, publishLocal them from dapr4s (`cd wasm && sbt publishLocal`).",
          ),
        identity,
      )
    require(files.nonEmpty, s"sbt-dapr4s-wasm: nothing resolved for $module")
    files
  }

  def witFetch(witDir: File, log: Logger): Unit = {
    val base = witDir.getParentFile
    val cmd = Seq("wkg", "wit", "fetch", "--config", (base / "wkg-config.toml").getPath)
    log.info(s"Running: ${cmd.mkString(" ")}")
    val exit = scala.sys.process.Process(cmd, base).!(scala.sys.process.ProcessLogger(log.info(_), log.info(_)))
    if (exit != 0) sys.error(s"wkg wit fetch failed ($exit)")
  }

  private def extractMainCoreModule(component: File, out: File): Unit = {
    val bytes = IO.readBytes(component)
    require(
      bytes.length > 8 && bytes(0) == 0 && bytes(1) == 'a' && bytes(2) == 's' && bytes(3) == 'm',
      s"$component is not a wasm binary",
    )
    var pos = 8
    def leb(): Int = {
      var result = 0
      var shift = 0
      var b = 0
      while ({
        b = bytes(pos) & 0xff
        pos += 1
        result |= (b & 0x7f) << shift
        shift += 7
        (b & 0x80) != 0
      }) ()
      result
    }
    val modules = scala.collection.mutable.ListBuffer.empty[Array[Byte]]
    while (pos < bytes.length) {
      val id = bytes(pos) & 0xff
      pos += 1
      val size = leb()
      if (id == 1) modules += java.util.Arrays.copyOfRange(bytes, pos, pos + size) // core module section
      pos += size
    }
    // `wasm-tools component new` adds tiny `wit-component:shim` / `:fixups` modules for indirect
    // lowering next to the linker's module; the linker's module is by far the largest.
    require(modules.nonEmpty, s"no core module in $component")
    IO.write(out, modules.maxBy(_.length))
  }

  private def wasmTools(log: Logger, args: String*): Unit = {
    val cmd = "wasm-tools" +: args
    log.info(s"Running: ${cmd.mkString(" ")}")
    val exit = scala.sys.process.Process(cmd).!(scala.sys.process.ProcessLogger(log.info(_), log.warn(_)))
    if (exit != 0) sys.error(s"wasm-tools failed ($exit): ${cmd.mkString(" ")}")
  }

  // -----------------------------------------------------------------------------------------------
  // Upstream blocker: the linker synthesizes the core module's `component-type` section from IR
  // signatures, where WIT type aliases are already dealiased (`use types.{metadata}` becomes an inline
  // list<tuple<string,string>>). The EXPORTED instance types then lack the alias type exports (e.g.
  // `metadata`) that the dapr-wasm-components inbound provider imports, and `wac compose` fails
  // ("instance is missing expected list export `metadata`"). Rebuild the component from the linked
  // core module with the REAL world: extract the core module, strip the synthesized section,
  // `wasm-tools component embed --encoding utf16` (scala-wasm lowers strings as UTF-16; the default
  // utf8 garbles every string) and `wasm-tools component new`. (`withWitDirectory` / `withWitWorld`
  // exist in WasmFeatures, but the 1.22.1-wasm.6 linker does not read them.) RETIRES when the linker
  // keeps aliases (or reads the WIT world): use its component as is.
  // -----------------------------------------------------------------------------------------------
  def buildComponent(log: Logger, linked: File, work: File, witDir: File, world: String, out: File): File = {
    IO.createDirectory(work)
    IO.createDirectory(out.getParentFile)
    val core = work / "core.wasm"
    val stripped = work / "core-stripped.wasm"
    val embedded = work / "core-embedded.wasm"
    extractMainCoreModule(linked, core)
    wasmTools(log, "strip", "--delete", "component-type.*", core.getPath, "-o", stripped.getPath)
    wasmTools(
      log,
      "component",
      "embed",
      "--encoding",
      "utf16",
      witDir.getPath,
      "--world",
      world,
      stripped.getPath,
      "-o",
      embedded.getPath,
    )
    wasmTools(log, "component", "new", embedded.getPath, "-o", out.getPath)
    wasmTools(log, "validate", out.getPath)
    out
  }
}
