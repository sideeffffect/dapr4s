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
  * `wasm/` build compiles this very file into its meta-build (`wasm/project/plugin-sources.sbt`), so the test
  * components in dapr4s and an external app (dapr4s-examples) build the same way.
  *
  * Two plugins, both opt-in (`enablePlugins`), both on top of the scala-wasm FORK of sbt-scalajs
  * (`io.github.scala-wasm:sbt-scalajs`), which this plugin depends on:
  *
  *   - [[Dapr4sWasmPlugin]]: for every project compiled by the Scala 3 nightly against dapr4s's wasm artifacts (library
  *     or app). Dependency exclusions + the patched linker.
  *   - [[Dapr4sWasmComponentPlugin]]: for a project linked as a WASI component (an app). WasmComponent linker config,
  *     the stdlib overlay, `componentWasm` / `componentWasmFull`, `dapr4sWasmWitFetch`.
  *
  * Prototype status: published only with `publishLocal` (never to Maven Central) — see dapr4s AGENTS.md "Wasm component
  * layer rules".
  */
object Dapr4sWasmPlugin extends AutoPlugin {
  override def requires: Plugins = ScalaJSPlugin
  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    /** The version of dapr4s's wasm artifacts this plugin was published with (they are published together, from the
      * same dapr4s commit). Use it for the library dependencies:
      * {{{
      * libraryDependencies += "com.github.sideeffffect" %%% "dapr4s-wasm" % dapr4sWasmVersion
      * }}}
      * Read from the plugin jar's manifest; inside dapr4s's own build (sources compiled into the meta-build, no jar)
      * there is no version and this fails.
      */
    lazy val dapr4sWasmVersion: String =
      Option(Dapr4sWasmPlugin.getClass.getPackage)
        .flatMap(p => Option(p.getImplementationVersion))
        .getOrElse(
          sys.error("dapr4sWasmVersion: no Implementation-Version (not running from the published plugin jar)"),
        )

    /** The `@WitExport` trampolines (Scala 2.13, `_sjs1_2.13`): a dependency of a COMPONENT only, never of a library or
      * a unit-tested project (its exports are linker entry points; a plain-JS link rejects them).
      */
    def dapr4sWasmWitExports(version: String): ModuleID =
      ("com.github.sideeffffect" % "dapr4s-wasm-wit-exports" % version).cross(CrossVersion.for3Use2_13With("sjs1_", ""))

    val dapr4sWasmPatchedLinkerVersion: String = Dapr4sWasm.PatchedLinkerVersion
  }

  // -----------------------------------------------------------------------------------------------
  // Upstream blocker 4 (scala-wasm linker: export params via linear memory are `???`): link with a
  // linker built from the 1.22.1-wasm.6 release commit + dapr4s's patch
  // (scripts/wasm/patches/scala-wasm-export-params-from-memory.patch), published to ~/.ivy2/local by
  // dapr4s's scripts/wasm/build-patched-linker.sh. Only the LINKER is replaced; plugin, library,
  // javalib and compiler plugin stay the released ones. A missing linker is a clear error.
  // -----------------------------------------------------------------------------------------------
  override def globalSettings: Seq[Setting[_]] = Seq(
    Global / scalaJSLinkerImpl / fullClasspath := {
      val s = streams.value
      val lm = (scalaJSLinkerImpl / dependencyResolution).value
      lm.retrieve(
        Dapr4sWasm.ScalaWasmOrg % "scalajs-linker_2.12" % Dapr4sWasm.PatchedLinkerVersion,
        scalaModuleInfo = None,
        s.cacheDirectory / "scalajs-linker" / Dapr4sWasm.PatchedLinkerVersion,
        s.log,
      ).fold(
        w =>
          sys.error(
            s"Patched scala-wasm linker ${Dapr4sWasm.PatchedLinkerVersion} not found in ~/.ivy2/local. Build it once " +
              s"per machine with dapr4s's scripts/wasm/build-patched-linker.sh (${w.resolveException.getMessage})",
          ),
        files => files.map(Attributed.blank(_)),
      )
    },
  )

  // -----------------------------------------------------------------------------------------------
  // A nightly-compiled project that depends on 2.13-compiled projects (dapr4s's generated WIT glue)
  // must NOT see the 2.13 stdlib the scala-wasm plugin adds to those (different organization, so no
  // eviction against the nightly's): drop it. Cross-built dependencies (scala-java-time, upickle, ...)
  // bring MAINLINE Scala.js (scalajs-library -> scalajs-javalib). Different organization than the
  // fork's, so no eviction either: both would be on the classpath and the first IR file per class
  // would win. Keep only the fork's.
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
    val dapr4sWasmComponentsDirectory = settingKey[File]("Where componentWasm writes <project name>.wasm")
    val dapr4sWasmWitFetch =
      taskKey[Unit]("`wkg wit fetch --config wkg-config.toml` next to the WIT directory (WIT only through OCI)")
    val dapr4sWasmStdlibOverlay = taskKey[File]("Directory with the fork-patched stdlib .sjsir overlay")
    val componentWasm = taskKey[File]("Linked app (fastLinkJS) as a WASI component typed by the real WIT world")
    val componentWasmFull = taskKey[File]("Like componentWasm, from fullLinkJS (optimized)")
  }
  import autoImport._

  override def buildSettings: Seq[Setting[_]] = Seq(
    dapr4sWasmWitDirectory := (LocalRootProject / baseDirectory).value / "wit",
    dapr4sWasmWitFetch := Dapr4sWasm.witFetch(dapr4sWasmWitDirectory.value, streams.value.log),
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    dapr4sWasmWorld := "app",
    dapr4sWasmComponentsDirectory := (LocalRootProject / target).value / "components",
    Compile / scalaJSLinkerConfig := {
      (Compile / scalaJSLinkerConfig).value
        .withESFeatures(_.withUseWebAssembly(true).withESVersion(ESVersion.ES2022))
        .withModuleKind(ModuleKind.WasmComponent)
    },
    // -----------------------------------------------------------------------------------------------
    // Upstream blocker 1: the nightly's Scala.js stdlib (org.scala-js:scalajs-scalalib_2.13:<nightly>)
    // still uses JS interop in a few classes (BoxesRunTime.equals uses JS `===`, reachable from any
    // program via Object.toString -> MurmurHash3 -> HashMap). scala-wasm's Scala 3 fork patched exactly
    // those library-js files (scala-wasm/scala3@17a0deeb). Their .sjsir go FIRST on the linker
    // classpath: the Scala.js IR loader keeps the first IR file per class name. Resolved with
    // `retrieve` (not libraryDependencies), so the exclusions above cannot touch it. Re-check on every
    // nightly bump (a nightly that changes those four files would be silently shadowed).
    // -----------------------------------------------------------------------------------------------
    dapr4sWasmStdlibOverlay := {
      val s = streams.value
      // NOT the project's `dependencyResolution`: with coursier it carries the project's
      // `excludeDependencies`, which drop exactly this artifact. The linker's resolution is global.
      val lm = (Global / scalaJSLinkerImpl / dependencyResolution).value
      val dir = target.value / "wasm-stdlib-overlay"
      val jars = lm
        .retrieve(
          (Dapr4sWasm.ScalaWasmOrg % "scalajs-scalalib_2.13" % Dapr4sWasm.StdlibOverlayVersion).intransitive(),
          scalaModuleInfo = None,
          s.cacheDirectory / "stdlib-overlay",
          s.log,
        )
        .fold(w => throw w.resolveException, identity)
      require(jars.nonEmpty, s"stdlib overlay: nothing resolved for ${Dapr4sWasm.StdlibOverlayVersion}")
      IO.delete(dir)
      jars.foreach { jar =>
        IO.unzip(
          jar,
          dir,
          (n: String) => n.endsWith(".sjsir") && Dapr4sWasm.OverlayClassPrefixes.exists(p => n.startsWith(p)),
        )
      }
      require(dir.isDirectory, s"stdlib overlay: no ${Dapr4sWasm.OverlayClassPrefixes.mkString(", ")} in $jars")
      dir
    },
    Compile / fullClasspath := Attributed.blank(dapr4sWasmStdlibOverlay.value) +: (Compile / fullClasspath).value,
    componentWasm := {
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
    componentWasmFull := {
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
    // The re-embed step reads the resolved WIT (wit/deps/), so fetch it first.
    componentWasm := componentWasm.dependsOn(dapr4sWasmWitFetch).value,
    componentWasmFull := componentWasmFull.dependsOn(dapr4sWasmWitFetch).value,
  )
}

object Dapr4sWasm {
  val ScalaWasmOrg = "io.github.scala-wasm"

  /** Must match `version` in scripts/wasm/build-patched-linker.sh's output (it reads this line). */
  val PatchedLinkerVersion = "1.22.1-wasm.6-dapr4s.1"

  /** scala-wasm's Scala 3 fork build, whose library-js has the four classes the nightly's library-js still implements
    * with JS interop.
    */
  val StdlibOverlayVersion = "3.8.3-RC1-wasm.4"
  val OverlayClassPrefixes: Seq[String] = Seq(
    "scala/runtime/BoxesRunTime",
    "scala/Symbol",
    "scala/collection/mutable/ArrayBuilder",
    "scala/collection/mutable/Buffer",
  )

  val exclusions: Seq[ExclusionRule] = Seq(
    ExclusionRule(ScalaWasmOrg, "scala-library"),
    ExclusionRule(ScalaWasmOrg, "scala3-library_3"),
    ExclusionRule(ScalaWasmOrg, "scala3-library_sjs1_3"),
    ExclusionRule(ScalaWasmOrg, "scalajs-scalalib_2.13"),
    ExclusionRule("org.scala-js", "scalajs-library_2.13"),
    ExclusionRule("org.scala-js", "scalajs-javalib"),
  )

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
  // Upstream blocker 5: the linker synthesizes the core module's `component-type` section from IR
  // signatures, where WIT type aliases are already dealiased (`use types.{metadata}` becomes an inline
  // list<tuple<string,string>>). The EXPORTED instance types then lack the alias type exports (e.g.
  // `metadata`) that the dapr-wasm-components inbound provider imports, and `wac compose` fails
  // ("instance is missing expected list export `metadata`"). Rebuild the component from the linked
  // core module with the REAL world: extract the core module, strip the synthesized section,
  // `wasm-tools component embed --encoding utf16` (scala-wasm lowers strings as UTF-16; the default
  // utf8 garbles every string) and `wasm-tools component new`. (`withWitDirectory` / `withWitWorld`
  // exist in WasmFeatures, but the 1.22.1-wasm.6 linker does not read them.)
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
