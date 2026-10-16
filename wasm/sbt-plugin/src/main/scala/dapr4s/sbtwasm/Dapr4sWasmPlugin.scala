package dapr4s.sbtwasm

import sbt._
import sbt.Keys._

import org.scalajs.linker.interface.{ESVersion, ModuleKind}
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

/** The build logic a dapr4s `wasm` app needs, as an sbt plugin (`sbt-dapr4s-wasm`).
  *
  * WHY a plugin and not a library: everything here is BUILD configuration, not code — the WasmComponent link, which
  * stdlib IR wins on the linker classpath, which transitive libraries must not reach the classpath. A library cannot
  * change any of that. dapr4s's own `wasm/` build compiles this very file into its meta-build
  * (`wasm/project/plugin-sources.sbt`) and points the artifact-backed key (`dapr4sWasmScalalibOverlay`) at its own
  * project, so the test components in dapr4s and an external app (dapr4s-examples) build the same way.
  *
  * Two plugins, both opt-in (`enablePlugins`), on top of the scala-wasm FORK of sbt-scalajs
  * (`io.github.scala-wasm:sbt-scalajs`), which this plugin depends on:
  *
  *   - [[Dapr4sWasmPlugin]]: every project compiled by the Scala 3 nightly against dapr4s's wasm artifacts (library or
  *     app): dependency exclusions.
  *   - [[Dapr4sWasmComponentPlugin]]: a project linked as a WASI component (an app): WasmComponent link, the stdlib
  *     overlay (`dapr4s-wasm-scalalib-overlay`), the `@WitExport` trampolines (`dapr4s-wasm-glue-exports`) and
  *     `dapr4sComponent` / `dapr4sComponentFull`.
  *
  * An app's whole setup:
  * {{{
  * // project/plugins.sbt
  * addSbtPlugin("com.github.sideeffffect" % "sbt-dapr4s-wasm" % "<version>")
  * // build.sbt
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
  }

  // -----------------------------------------------------------------------------------------------
  // A nightly-compiled project that depends on 2.13-compiled artifacts (dapr4s's WIT glue) must NOT
  // see the fork's 2.13 stdlib (io.github.scala-wasm:scalajs-scalalib_2.13:2.13.18+<v>) that the
  // scala-wasm plugin adds to those: it is a different organization AND version line than the
  // nightly's own Scala.js stdlib (org.scala-js:scalajs-scalalib_2.13:<nightly>), so nothing evicts
  // it, both end up on the classpath and the first IR file per class wins. Drop the fork's.
  // (The UPSTREAM org.scala-js core libraries -- scalajs-library, scalajs-javalib, ... -- that dapr4s
  // core and other cross-built dependencies bring are excluded by the scala-wasm plugin itself since
  // https://github.com/scala-wasm/scala-wasm/pull/271, which keeps the upstream scalalib on Scala
  // 3.8+ and leaves this duplicate open.) RETIRES when the scala-wasm plugin drops the 2.13 scalalib
  // from Scala 3 projects.
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
    val dapr4sWasmComponentsDirectory = settingKey[File]("Where dapr4sComponent writes <project name>.wasm")
    val dapr4sWasmScalalibOverlay =
      taskKey[Seq[File]]("The fork-patched stdlib .sjsir (dapr4s-wasm-scalalib-overlay), first on the linker classpath")
    val dapr4sWasmAddGlueExports =
      settingKey[Boolean]("Add dapr4s-wasm-glue-exports (the @WitExport trampolines) to libraryDependencies")
    val dapr4sComponent = taskKey[File]("Linked app (fastLinkJS) as a WASI component")
    val dapr4sComponentFull = taskKey[File]("Like dapr4sComponent, from fullLinkJS (optimized)")
  }
  import autoImport._
  import Dapr4sWasmPlugin.autoImport.dapr4sWasmVersion

  override def buildSettings: Seq[Setting[_]] = Seq(
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
    // The linker's output IS the component: its component-type, synthesized from the IR signatures,
    // keeps the WIT type aliases the dapr-wasm-components providers need (`metadata` in the exported
    // `*-callback` instances) since https://github.com/scala-wasm/scala-wasm/pull/269, given bindings
    // that reference the defining interface (dapr4s-wasm-glue's are rewritten so, see `useWitAliases`
    // in dapr4s's wasm/build.sbt). It lowers strings as UTF-16 itself. Nothing to re-embed: an app
    // needs no WIT files and no wkg (wasm-tools: only the one the scala-wasm linker runs itself).
    dapr4sComponent := {
      (Compile / fastLinkJS).value
      Dapr4sWasm.copyComponent(
        (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm",
        dapr4sWasmComponentsDirectory.value / s"${name.value}.wasm",
      )
    },
    dapr4sComponentFull := {
      (Compile / fullLinkJS).value
      Dapr4sWasm.copyComponent(
        (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / "main.wasm",
        dapr4sWasmComponentsDirectory.value / s"${name.value}-opt.wasm",
      )
    },
  )
}

object Dapr4sWasm {
  val Org = "com.github.sideeffffect"
  val ScalaWasmOrg = "io.github.scala-wasm"

  val exclusions: Seq[ExclusionRule] = Seq(ExclusionRule(ScalaWasmOrg, "scalajs-scalalib_2.13"))

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

  // `wkg wit fetch` for dapr4s's own build (the WIT world its dapr4s-wasm-glue bindings are generated
  // from). Apps need no WIT any more (see dapr4sComponent).
  def witFetch(witDir: File, log: Logger): Unit = {
    val base = witDir.getParentFile
    val cmd = Seq("wkg", "wit", "fetch", "--config", (base / "wkg-config.toml").getPath)
    log.info(s"Running: ${cmd.mkString(" ")}")
    val exit = scala.sys.process.Process(cmd, base).!(scala.sys.process.ProcessLogger(log.info(_), log.info(_)))
    if (exit != 0) sys.error(s"wkg wit fetch failed ($exit)")
  }

  def copyComponent(component: File, out: File): File = {
    require(component.isFile, s"the linker wrote no $component")
    IO.copyFile(component, out)
    out
  }
}
