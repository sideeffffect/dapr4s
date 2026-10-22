package dapr4s.millwasm

import mill.*
import mill.scalalib.*
import mill.scalajslib.*

/** The build logic a dapr4s `wasm` app needs, as a Mill plugin (`mill-dapr4s-wasm`), the twin of the sbt plugin
  * `sbt-dapr4s-wasm` (dapr4s's wasm/sbt-plugin). Keep the two in sync.
  *
  * WHY a plugin and not a library: everything here is a workaround in the BUILD, not in code: which Scala.js
  * organization compiles and links the app, which linker links it, which stdlib IR wins on the linker classpath, which
  * transitive libraries must not reach the classpath, and how the linked module becomes a component typed by the real
  * WIT world. A library cannot change any of that.
  *
  * dapr4s's own build compiles these very sources into its meta-build (they live in `mill-build/src`) and points the
  * artifact-backed tasks ([[Dapr4sWasmComponentModule.dapr4sWasmLinkerClasspath]],
  * [[Dapr4sWasmComponentModule.dapr4sWasmScalalibOverlay]]) at its own modules, so dapr4s's test components and an
  * external app (dapr4s-examples) build the same way.
  *
  * The traits, from the most general:
  *
  *   - [[ScalaWasmModule]]: a Scala.js module on the scala-wasm FORK (`io.github.scala-wasm`) instead of mainline
  *     `org.scala-js`;
  *   - [[Dapr4sWasmModule]]: a module compiled by Scala 3 against dapr4s's wasm artifacts (library or app): + the
  *     dependency exclusions;
  *   - [[Dapr4sWasmTests]]: its plain-JS unit tests (Node);
  *   - [[WitModule]] / [[WitBindgenModule]]: `wkg wit fetch` (WIT only through OCI) / + wit-bindgen-scala (a Scala 2.13
  *     module of hand-written WIT code; an app built on dapr4s's API does not need one);
  *   - [[Dapr4sWasmComponentModule]]: a dapr4s app linked as a WASI component (`dapr4sComponent`).
  *
  * An app's whole setup:
  * {{{
  * //| mvnDeps: ["com.github.sideeffffect::mill-dapr4s-wasm::<version>"]
  * import dapr4s.millwasm.*
  * object app extends Dapr4sWasmComponentModule {   // + app/wit/world.wit, app/wkg-config.toml, app/wkg.lock
  *   def scalaVersion = "<the Scala 3 nightly dapr4s is built with>"
  *   def mvnDeps = Seq(mvn"com.github.sideeffffect::dapr4s-wasm::\$dapr4sWasmVersion")
  * }
  * // + `object dapr4s.wasm.ComponentRoot { def component: DaprComponent = MyComponent }`
  * }}}
  *
  * Each workaround is documented at its definition with the upstream change that RETIRES it.
  */
object Dapr4sWasm {
  val Org = "com.github.sideeffffect"

  // The scala-wasm release dapr4s's wasm artifacts are compiled and linked with (the newest on Central).
  val ScalaWasmOrg = "io.github.scala-wasm"
  val ScalaWasmVersion = "1.22.1-wasm.6"

  /** The version of dapr4s's wasm artifacts this plugin was published with (they are published together, from the same
    * dapr4s commit, at the same version). Read from a resource of the plugin jar; dapr4s's own build compiles these
    * sources without it and never asks for it (its modules override every task that would).
    */
  lazy val version: String =
    Option(getClass.getResourceAsStream("/dapr4s/millwasm/version"))
      .map(in =>
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        finally in.close(),
      )
      .getOrElse(sys.error("mill-dapr4s-wasm: no version resource (not running from the published plugin jar)"))

  // -----------------------------------------------------------------------------------------------
  // A Scala 3 module that depends on 2.13-compiled artifacts (dapr4s's WIT glue) must NOT see the 2.13
  // stdlib the fork adds to those (different organization, so no eviction against the nightly's): drop
  // it. dapr4s core (dapr4s_sjs1_3) and other cross-built dependencies (scala-java-time, upickle, ...)
  // bring MAINLINE Scala.js (scalajs-library -> scalajs-javalib). Different organization than the fork's,
  // so no eviction either: both would be on the classpath and the first IR file per class would win.
  // Keep only the fork's. RETIRES with scala-wasm/scala-wasm#271 (the fork's core libraries evict
  // mainline's) or when mainline Scala.js gains the component target.
  // -----------------------------------------------------------------------------------------------
  val Exclusions: Seq[(String, String)] = Seq(
    ScalaWasmOrg -> "scala-library",
    ScalaWasmOrg -> "scala3-library_3",
    ScalaWasmOrg -> "scala3-library_sjs1_3",
    ScalaWasmOrg -> "scalajs-scalalib_2.13",
    "org.scala-js" -> "scalajs-library_2.13",
    "org.scala-js" -> "scalajs-javalib",
  )

  /** `wkg wit fetch` on a copy of the WIT inputs in `work`: `world.wit` goes to `work/wit/`, `wkg-config.toml` and
    * `wkg.lock` next to it. Returns the resolved WIT directory (`world.wit` + `deps/`). WIT only through OCI: the world
    * `include`s a published package, wkg-config.toml maps its namespace to the registry, wkg.lock pins the digest. The
    * fetch never writes into the source tree.
    */
  def witFetch(world: os.Path, config: os.Path, lock: os.Path, work: os.Path): os.Path = {
    os.copy.over(world, work / "wit" / "world.wit", createFolders = true)
    os.copy.over(config, work / "wkg-config.toml")
    os.copy.over(lock, work / "wkg.lock")
    os.proc("wkg", "wit", "fetch", "--config", "wkg-config.toml").call(cwd = work, stderr = os.Pipe)
    work / "wit"
  }

  // -----------------------------------------------------------------------------------------------
  // The linker synthesizes the core module's `component-type` section from IR signatures, where WIT
  // type aliases are already dealiased (`use types.{metadata}` becomes an inline
  // list<tuple<string,string>>). The EXPORTED instance types then lack the alias type exports (e.g.
  // `metadata`) that the dapr-wasm-components inbound provider imports, and `wac compose` fails
  // ("instance is missing expected list export `metadata`"). Rebuild the component from the linked core
  // module with the REAL world: extract the core module, strip the synthesized section,
  // `wasm-tools component embed --encoding utf16` (scala-wasm lowers strings as UTF-16; the default
  // utf8 garbles every string) and `wasm-tools component new`. RETIRES with scala-wasm/scala-wasm#269
  // (the linker keeps the WIT aliases): use the linker's component as is.
  // -----------------------------------------------------------------------------------------------
  def buildComponent(linked: os.Path, work: os.Path, witDir: os.Path, world: String): os.Path = {
    os.makeDir.all(work)
    val core = work / "core.wasm"
    val stripped = work / "core-stripped.wasm"
    val embedded = work / "core-embedded.wasm"
    val out = work / "component.wasm"
    os.write.over(core, extractMainCoreModule(linked))
    wasmTools("strip", "--delete", "component-type.*", core, "-o", stripped)
    wasmTools("component", "embed", "--encoding", "utf16", witDir, "--world", world, stripped, "-o", embedded)
    wasmTools("component", "new", embedded, "-o", out)
    wasmTools("validate", out)
    out
  }

  private def wasmTools(args: os.Shellable*): Unit = {
    val _ = os.proc(("wasm-tools": os.Shellable) +: args).call(stderr = os.Pipe)
  }

  /** The linker's core module: `wasm-tools component new` adds tiny `wit-component:shim` / `:fixups` modules for
    * indirect lowering next to it; the linker's module is by far the largest.
    */
  private def extractMainCoreModule(component: os.Path): Array[Byte] = {
    val bytes = os.read.bytes(component)
    require(
      bytes.length > 8 && bytes(0) == 0 && bytes(1) == 'a' && bytes(2) == 's' && bytes(3) == 'm',
      s"$component is not a wasm binary",
    )
    var pos = 8
    def leb(): Int = {
      var result = 0
      var shift = 0
      var b = 0
      while {
        b = bytes(pos) & 0xff
        pos += 1
        result |= (b & 0x7f) << shift
        shift += 7
        (b & 0x80) != 0
      } do ()
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
    require(modules.nonEmpty, s"no core module in $component")
    modules.maxBy(_.length)
  }
}

import Dapr4sWasm.*

/** A Scala.js module on the scala-wasm fork (`io.github.scala-wasm`) instead of mainline `org.scala-js`.
  *
  * WHAT: overrides of every task that names the `org.scala-js` organization: the Scala.js library (and, for Scala 2.13,
  * the stdlib IR), the compiler plugin, the linker in Mill's Scala.js worker. WHY: Mill's ScalaJSModule has no setting
  * for the Scala.js organization. WHY SAFE: the fork keeps mainline's package and class names, so Mill's Scala.js
  * worker drives the fork's linker unchanged (for the plain-JS links of unit tests; components are linked by the
  * patched linker, see [[Dapr4sWasmComponentModule]]). RETIRES with com-lihaoyi/mill#7783 (a configurable Scala.js
  * organization).
  */
trait ScalaWasmModule extends ScalaJSModule {
  override def scalaJSVersion: T[String] = Task(ScalaWasmVersion)

  override def scalacPluginMvnDeps: T[Seq[Dep]] = Task {
    super.scalacPluginMvnDeps().filterNot(_.dep.module.organization.value == "org.scala-js") ++
      Option.when(!scalaVersion().startsWith("3."))(mvn"$ScalaWasmOrg:::scalajs-compiler:${scalaJSVersion()}")
  }

  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    val sv = scalaVersion()
    super.mandatoryMvnDeps().filterNot(_.dep.module.organization.value == "org.scala-js") ++
      Seq(mvn"$ScalaWasmOrg::scalajs-library:${scalaJSVersion()}".withDottyCompat(sv)) ++
      Option.when(sv.startsWith("2."))(mvn"$ScalaWasmOrg::scalajs-scalalib:$sv+${scalaJSVersion()}")
  }

  // Plain-JS linking (unit tests on Node) uses the fork's released 2.13 linker in Mill's Scala.js worker.
  override def scalaJSLinkerClasspath: T[Seq[PathRef]] = Task {
    jvmWorker()
      .defaultResolver()
      .classpath(
        Seq(
          mvn"$ScalaWasmOrg:scalajs-linker_2.13:${scalaJSVersion()}",
          mvn"$ScalaWasmOrg:scalajs-sbt-test-adapter_2.13:${scalaJSVersion()}",
          mvn"org.scala-js:scalajs-env-nodejs_2.13:1.4.0",
        ).map(Lib.depToBoundDep(_, mill.util.BuildInfo.scalaVersion, "")),
      )
  }
}

/** A module compiled by Scala 3 against dapr4s's wasm artifacts (a library or an app): the fork + the dependency
  * exclusions (see [[Dapr4sWasm.Exclusions]]) on every dependency.
  */
trait Dapr4sWasmModule extends ScalaWasmModule {

  /** The version of dapr4s's wasm artifacts that matches this plugin. */
  def dapr4sWasmVersion: String = Dapr4sWasm.version

  override def allMvnDeps: T[Seq[Dep]] = Task(super.allMvnDeps().map(_.exclude(Exclusions*)))
}

/** Plain-JS unit tests (Node) of a [[Dapr4sWasmModule]]: the fork's test bridge instead of mainline's, with the
  * exclusions. Mill prepends `scalaJSTestDeps` to the test link classpath, so they must not bring the 2.13 stdlib.
  */
trait Dapr4sWasmTests extends TestScalaJSModule with Dapr4sWasmModule {
  override def scalaJSTestDeps: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        mvn"$ScalaWasmOrg::scalajs-library:${scalaJSVersion()}",
        mvn"$ScalaWasmOrg::scalajs-test-bridge:${scalaJSVersion()}",
      ).map(_.withDottyCompat(scalaVersion()).exclude(Exclusions*)),
    )
  }
}

/** The WIT world of a module, resolved through OCI. */
trait WitModule extends Module {

  /** `world.wit` (`include dapr-wasm-components:interfaces/app@<version>;`), `wkg-config.toml`, `wkg.lock`, in this
    * order. Default: `wit/world.wit`, `wkg-config.toml` and `wkg.lock` in the module directory.
    */
  def witFiles: T[Seq[PathRef]] = Task.Sources("wit/world.wit", "wkg-config.toml", "wkg.lock")

  /** The resolved WIT directory (`world.wit` + `deps/`), from `wkg wit fetch`. */
  def wit: T[PathRef] = Task {
    witFiles().map(_.path) match {
      case Seq(world, config, lock) => PathRef(witFetch(world, config, lock, Task.dest))
      case other                    => Task.fail(s"witFiles: expected world.wit, wkg-config.toml, wkg.lock; got $other")
    }
  }

  /** The WIT world. */
  def witWorld: String = "app"
}

/** A Scala 2.13 module with WIT bindings generated by wit-bindgen-scala (the generator the fork's sbt plugin runs, with
  * the same arguments). Only Scala 2.13 + the fork's compiler plugin lower WIT imports and emit `@WitExport`: mainline
  * Scala 3 has no WIT codegen. RETIRES with com-lihaoyi/mill#7784 (a wit-bindgen-scala task in Mill's ScalaJSModule).
  */
trait WitBindgenModule extends ScalaWasmModule with WitModule {

  /** The root package of the generated bindings. */
  def witBindgenBasePackage: String

  def witBindings: T[PathRef] = Task {
    val out = Task.dest / "wit-bindgen"
    os.makeDir.all(out)
    os.proc(
      "wit-bindgen-scala",
      wit().path,
      "--out-dir",
      out,
      "--world",
      witWorld,
      "--base-package",
      witBindgenBasePackage,
    ).call(stderr = os.Pipe, stdout = os.Pipe)
    PathRef(out)
  }
  override def generatedSources: T[Seq[PathRef]] = Task(super.generatedSources() :+ witBindings())
}

/** A dapr4s app linked as a WASI component for dapr-wasm-components: `dapr4sComponent` (`dapr4sComponentFull`: from the
  * optimized link) is the component, typed by the real WIT world ([[WitModule.wit]]).
  *
  * WHY a link driver and not Mill's own linking: Mill's Scala.js worker is compiled against mainline Scala.js, whose
  * linker interface has no `ModuleKind.WasmComponent`. The driver (`dapr4s.wasmlink.WasmLink`, shipped in
  * dapr4s-wasm-linker) calls the fork's linker through its public API, in a separate JVM. RETIRES with
  * com-lihaoyi/mill#7783 (`ModuleKind.WasmComponent` in Mill's ScalaJSModule); com-lihaoyi/mill#7785 then adds running
  * and testing components (wasmtime).
  */
trait Dapr4sWasmComponentModule extends Dapr4sWasmModule with WitModule {

  // -----------------------------------------------------------------------------------------------
  // The released scala-wasm linker leaves the EXPORT side of WIT params via linear memory (> 16 flat
  // params) a `???` and silently emits nothing on the IMPORT side. Link with dapr4s-wasm-linker_2.12,
  // the release rebuilt with dapr4s's patch, republished under dapr4s's coordinates with scala-wasm's
  // LICENSE/NOTICE (+ the link driver). Only the LINKER is replaced; the library, javalib and compiler
  // plugin stay the released ones. RETIRES with scala-wasm/scala-wasm#268 (params via memory on both
  // sides), together with the driver (see above): link with the released linker.
  // -----------------------------------------------------------------------------------------------
  /** Classpath of the linker + link driver (default: `dapr4s-wasm-linker_2.12` and its dependencies). */
  def dapr4sWasmLinkerClasspath: T[Seq[PathRef]] = Task {
    dapr4sWasmResolveTool(Seq(mvn"$Org:dapr4s-wasm-linker_2.12:$dapr4sWasmVersion"))()
  }

  /** Resolve build TOOLING (not dependencies of the app) from this module's repositories. NOT with `defaultResolver()`:
    * a ScalaModule's resolver forces scala-library to the module's Scala version, which would put the Scala 3 library
    * under the Scala 2.12 linker (`NoClassDefFoundError: scala/Serializable`); and the app's exclusions must not apply
    * either.
    */
  def dapr4sWasmResolveTool(deps: Seq[Dep]): Task[Seq[PathRef]] = Task.Anon {
    Lib
      .resolveDependencies(
        repositories = repositoriesTask(),
        deps = deps.map(Lib.depToBoundDep(_, scalaVersion(), "")),
        ctx = Some(Task.ctx()),
      )
      .toEither
      .fold(e => Task.fail(e), identity)
  }

  // -----------------------------------------------------------------------------------------------
  // The Scala 3 stdlib IR (org.scala-js:scalajs-scalalib_2.13:<Scala 3 version>) still uses JS
  // interop: BoxesRunTime.equals uses JS `===`, reachable from any program; Symbol's UniquenessCache
  // is a js.Dictionary; ... scala-wasm's Scala 3 fork patched exactly those files;
  // dapr4s-wasm-scalalib-overlay carries their .sjsir and goes FIRST on the linker classpath (the IR
  // loader keeps the first IR file per class name). Resolved on its own, intransitively (never a
  // dependency: the module's exclusions would apply). RETIRES when the Scala 3 stdlib's Scala.js
  // sources are Wasm-clean (scala-wasm's Scala 3 patches upstreamed): drop the overlay.
  // -----------------------------------------------------------------------------------------------
  /** The fork-patched stdlib .sjsir (default: `dapr4s-wasm-scalalib-overlay`). */
  def dapr4sWasmScalalibOverlay: T[Seq[PathRef]] = Task {
    dapr4sWasmResolveTool(Seq(mvn"$Org:dapr4s-wasm-scalalib-overlay:$dapr4sWasmVersion".exclude("*" -> "*")))()
  }

  /** Add `dapr4s-wasm-glue-exports` (the `@WitExport` trampolines) to the dependencies. They are a COMPONENT's
    * dependency only: every `@WitExport` is a linker entry point, which a plain-JS link (unit tests) rejects. Hence not
    * a dependency of dapr4s-wasm.
    */
  def dapr4sWasmAddGlueExports: Boolean = true

  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    super.mandatoryMvnDeps() ++ Option.when(dapr4sWasmAddGlueExports)(
      mvn"$Org::dapr4s-wasm-glue-exports::$dapr4sWasmVersion".withDottyCompat(scalaVersion()),
    )
  }

  def dapr4sWasmLinked(full: Boolean): Task[PathRef] = Task.Anon {
    val out = Task.dest / "linked"
    os.makeDir.all(out)
    val cp = (dapr4sWasmScalalibOverlay() ++ runClasspath()).map(_.path).filter(os.exists)
    mill.util.Jvm.callProcess(
      mainClass = "dapr4s.wasmlink.WasmLink",
      classPath = dapr4sWasmLinkerClasspath().map(_.path),
      mainArgs = Seq[String](if (full) "full" else "fast", out.toString) ++ cp.map(_.toString),
      stdout = os.Inherit,
    )
    PathRef(out / "main.wasm")
  }

  /** The app as a WASI component typed by the real WIT world (fast link). */
  def dapr4sComponent: T[PathRef] = Task {
    PathRef(buildComponent(dapr4sWasmLinked(full = false)().path, Task.dest, wit().path, witWorld))
  }

  /** Like [[dapr4sComponent]], from the optimized link. */
  def dapr4sComponentFull: T[PathRef] = Task {
    PathRef(buildComponent(dapr4sWasmLinked(full = true)().path, Task.dest, wit().path, witWorld))
  }
}
