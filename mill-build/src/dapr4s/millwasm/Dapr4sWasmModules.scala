package dapr4s.millwasm

import mill.*
import mill.scalalib.*
import mill.scalajslib.*
import mill.scalajslib.api.{ESFeatures, ESVersion, ModuleKind, Report}

/** The build logic a dapr4s `wasm` app needs, as a Mill plugin (`mill-dapr4s-wasm`), the twin of the sbt plugin
  * `sbt-dapr4s-wasm` (dapr4s's wasm/sbt-plugin). Keep the two in sync.
  *
  * WHY a plugin and not a library: everything here is BUILD configuration, not code: which Scala.js organization
  * compiles and links the app, the WasmComponent link, which stdlib IR wins on the linker classpath, which transitive
  * libraries must not reach the classpath. A library cannot change any of that.
  *
  * dapr4s's own build compiles these very sources into its meta-build (they live in `mill-build/src`) and points the
  * artifact-backed task ([[Dapr4sWasmComponentModule.dapr4sWasmScalalibOverlay]]) at its own module, so dapr4s's test
  * components and an external app (dapr4s-examples) build the same way.
  *
  * The traits, from the most general:
  *
  *   - [[ScalaWasmModule]]: a Scala.js module on the scala-wasm FORK (`io.github.scala-wasm`) instead of mainline
  *     `org.scala-js`;
  *   - [[Dapr4sWasmModule]]: a module compiled by Scala 3 against dapr4s's wasm artifacts (library or app): + the
  *     dependency exclusions;
  *   - [[Dapr4sWasmTests]]: its plain-JS unit tests (Node);
  *   - [[WitModule]] / [[WitBindgenModule]]: `wkg wit fetch` (WIT only through OCI) / + wit-bindgen-scala (a Scala 2.13
  *     module of hand-written WIT code; an app built on dapr4s's API needs neither);
  *   - [[Dapr4sWasmComponentModule]]: a dapr4s app linked as a WASI component (`dapr4sComponent`): the linker's
  *     component as is, so an app needs no WIT files and no wkg.
  *
  * An app's whole setup:
  * {{{
  * //| mvnDeps: ["com.github.sideeffffect::mill-dapr4s-wasm::<version>"]
  * import dapr4s.millwasm.*
  * object app extends Dapr4sWasmComponentModule {
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

  // The scala-wasm dapr4s's wasm artifacts are compiled and linked with. 1.22.1-wasm.7-dapr4s-local is NOT on Maven
  // Central: dapr4s's scripts/wasm/build-scala-wasm.sh builds scala-wasm's `scala-wasm` branch head (it has the merged
  // scala-wasm/scala-wasm#268, #269 and #271, not released yet) and publishLocal's it to ~/.ivy2/local, which Mill's
  // default repositories include. RETIRES with a scala-wasm release that has all three: pin it here (the newest on
  // Central is 1.22.1-wasm.6).
  val ScalaWasmOrg = "io.github.scala-wasm"
  val ScalaWasmVersion = "1.22.1-wasm.7-dapr4s-local"

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
  // Keep only the fork's. scala-wasm/scala-wasm#271 does this in the fork's SBT plugin only (sbt-dapr4s-wasm
  // dropped the org.scala-js exclusions with it); Mill's `scalaJSOrganization` (mill#7783) swaps the
  // organization of the artifacts Mill adds itself, not of transitive ones, so this plugin keeps all of
  // them. RETIRES when Mill's Scala.js support excludes the other organization's core libraries, or when
  // mainline Scala.js gains the component target.
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

  /** Copy the linker's component to `out`. The linker's output IS the component: its component-type, synthesized from
    * the IR signatures, keeps the WIT type aliases the dapr-wasm-components providers need (`metadata` in the exported
    * `*-callback` instances) since scala-wasm/scala-wasm#269, given bindings that reference the defining interface
    * ([[WitBindgenModule]] rewrites them so, see [[useWitAliases]]), and it lowers strings as UTF-16 itself. (Until
    * 2026-10-16: extract the core module, strip `component-type.*`, `wasm-tools component embed --encoding utf16` the
    * real WIT world, `component new`.)
    */
  // -----------------------------------------------------------------------------------------------
  // WHAT: rewrites generated bindings in place so that every `use`d WIT type ALIAS points at its
  // defining interface: in `invocation_callback`, `type Metadata = Array[wit.Tuple2[String, String]]`
  // becomes `type Metadata = dapr4swit.dapr_wasm_components.interfaces.types.Metadata`.
  // WHY: wit-bindgen-scala 0.1.0 renders a `use` of a type alias structurally (its `render_type` writes
  // named list/option/result/tuple typedefs out), so the `@WitAlias` of `types.metadata` is lost and the
  // linker's component-type has no `metadata` type export in the exported `*-callback` instances;
  // `wac compose` with the dapr-wasm-components inbound provider then fails ("instance is missing
  // expected list export `metadata`"). The scala-wasm compiler keeps an alias reached through a plain
  // type alias since scala-wasm/scala-wasm#269, but only in this form.
  // WHY SAFE: a rewrite replaces a type with an alias of the SAME structural type (both right-hand sides
  // are checked equal before rewriting), so no Scala type changes; only the alias the compiler sees does.
  // It applies only to an un-annotated `type X = R` whose name has exactly one `@WitAlias` definition
  // `type X = R` (same R) elsewhere in the bindings, i.e. a `use`. Idempotent: a rewritten line no longer
  // matches. Verified by dapr4s's scripts/wasm/e2e.sh, which composes the linker's component AS IS.
  // RETIRES when wit-bindgen-scala renders `use`d aliases as references to the defining interface.
  // -----------------------------------------------------------------------------------------------
  def useWitAliases(dir: os.Path): Unit = {
    val TypeLine = """^  type (\w+) = (.+)$""".r
    val Annotation = "@scala.scalajs.wit.annotation.WitAlias("
    val files = os.walk(dir).filter(f => os.isFile(f) && f.ext == "scala")
    def objectOf(src: String): String = {
      val pkg = """(?m)^package ([\w.]+)$""".r.findFirstMatchIn(src).map(_.group(1))
      val obj = """(?m)^package object (\w+)""".r.findFirstMatchIn(src).map(_.group(1))
      (pkg.toList ++ obj.toList).mkString(".")
    }
    // name -> (FQN of the defining package object, right-hand side), for every @WitAlias definition.
    val defs = files.flatMap { f =>
      val lines = os.read.lines(f)
      lines.indices.flatMap { i =>
        if (i > 0 && lines(i - 1).contains(Annotation)) lines(i) match {
          case TypeLine(n, rhs) => Some((n, (objectOf(os.read(f)), rhs)))
          case _                => None
        }
        else None
      }
    }
    val unique = defs.groupBy(_._1).collect { case (n, Seq((_, d))) => n -> d }
    files.foreach { f =>
      val lines = os.read.lines(f)
      val rewritten = lines.indices.map { i =>
        val annotated = i > 0 && lines(i - 1).contains(Annotation)
        lines(i) match {
          case TypeLine(n, rhs) if !annotated && unique.get(n).exists(_._2 == rhs) => s"  type $n = ${unique(n)._1}.$n"
          case l                                                                   => l
        }
      }
      if (rewritten != lines) os.write.over(f, rewritten.mkString("", "\n", "\n"))
    }
  }

  def copyComponent(component: os.Path, out: os.Path): os.Path = {
    if (!os.isFile(component)) sys.error(s"the linker wrote no $component")
    os.copy.over(component, out, createFolders = true)
    out
  }
}

import Dapr4sWasm.*

/** A Scala.js module on the scala-wasm fork (`io.github.scala-wasm`) instead of mainline `org.scala-js`: Mill's
  * `scalaJSOrganization` (com-lihaoyi/mill#7783) swaps the Scala.js library (and, for Scala 2.13, the stdlib IR and the
  * compiler plugin), the test bridge and the linker in Mill's Scala.js worker.
  */
trait ScalaWasmModule extends ScalaJSModule {
  override def scalaJSVersion: T[String] = Task(ScalaWasmVersion)
  override def scalaJSOrganization: T[String] = Task(ScalaWasmOrg)
}

/** A module compiled by Scala 3 against dapr4s's wasm artifacts (a library or an app): the fork + the dependency
  * exclusions (see [[Dapr4sWasm.Exclusions]]) on every dependency.
  */
trait Dapr4sWasmModule extends ScalaWasmModule {

  /** The version of dapr4s's wasm artifacts that matches this plugin. */
  def dapr4sWasmVersion: String = Dapr4sWasm.version

  override def allMvnDeps: T[Seq[Dep]] = Task(super.allMvnDeps().map(_.exclude(Exclusions*)))
}

/** Plain-JS unit tests (Node) of a [[Dapr4sWasmModule]]: the fork's test bridge (`scalaJSOrganization`), with the
  * exclusions.
  */
trait Dapr4sWasmTests extends TestScalaJSModule with Dapr4sWasmModule

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
    useWitAliases(out)
    PathRef(out)
  }
  override def generatedSources: T[Seq[PathRef]] = Task(super.generatedSources() :+ witBindings())
}

/** A dapr4s app linked as a WASI component for dapr-wasm-components: `dapr4sComponent` (`dapr4sComponentFull`: from the
  * optimized link) is the component. Mill's own `fastLinkJS` / `fullLinkJS` link it, with `ModuleKind.WasmComponent`
  * (com-lihaoyi/mill#7783; the fork's linker runs `wasm-tools component new` itself), and the linker's component is
  * used as is: an app needs no WIT files and no wkg. Running and testing a component in Mill (wasmtime) is
  * com-lihaoyi/mill#7785; until then the e2e scripts run it.
  */
trait Dapr4sWasmComponentModule extends Dapr4sWasmModule {

  override def moduleKind: T[ModuleKind] = Task(ModuleKind.WasmComponent)
  override def scalaJSUseWebAssembly: T[Boolean] = Task(true)
  override def esFeatures: T[ESFeatures] = Task(super.esFeatures().withESVersion(ESVersion.ES2022))

  /** Resolve build TOOLING (not dependencies of the app) from this module's repositories. NOT with `defaultResolver()`:
    * a ScalaModule's resolver forces scala-library to the module's Scala version, which would put the Scala 3 library
    * under the linker; and the app's exclusions must not apply either.
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
  // dapr4s-wasm-scalalib-overlay carries their .sjsir and goes FIRST on the run (= linker) classpath
  // (the IR loader keeps the first IR file per class name). Resolved on its own, intransitively (never a
  // dependency: the module's exclusions would apply). RETIRES when the Scala 3 stdlib's Scala.js
  // sources are Wasm-clean (scala-wasm's Scala 3 patches upstreamed): drop the overlay.
  // -----------------------------------------------------------------------------------------------
  /** The fork-patched stdlib .sjsir (default: `dapr4s-wasm-scalalib-overlay`). */
  def dapr4sWasmScalalibOverlay: T[Seq[PathRef]] = Task {
    dapr4sWasmResolveTool(Seq(mvn"$Org:dapr4s-wasm-scalalib-overlay:$dapr4sWasmVersion".exclude("*" -> "*")))()
  }

  override def runClasspath: T[Seq[PathRef]] = Task(dapr4sWasmScalalibOverlay() ++ super.runClasspath())

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

  private def linkedComponent(report: Report): os.Path = report.dest.path / "main.wasm"

  /** The app as a WASI component (fast link). */
  def dapr4sComponent: T[PathRef] = Task {
    PathRef(copyComponent(linkedComponent(fastLinkJS()), Task.dest / "component.wasm"))
  }

  /** Like [[dapr4sComponent]], from the optimized link. */
  def dapr4sComponentFull: T[PathRef] = Task {
    PathRef(copyComponent(linkedComponent(fullLinkJS()), Task.dest / "component.wasm"))
  }
}
