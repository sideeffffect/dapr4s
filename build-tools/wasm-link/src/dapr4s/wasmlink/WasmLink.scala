package dapr4s.wasmlink

import java.nio.file.{Path, Paths}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration

import org.scalajs.linker.{PathIRContainer, PathOutputDirectory, StandardImpl}
import org.scalajs.linker.interface.{ESVersion, ModuleKind, StandardConfig}
import org.scalajs.logging.{Level, ScalaConsoleLogger}

/** Links a dapr4s `wasm` app as a WASI component (`ModuleKind.WasmComponent`) with dapr4s-wasm-linker (the
  * scala-wasm linker + dapr4s's params-via-memory patch). The `wasm.*` component modules of build.mill run it.
  *
  * WHY a driver and not Mill's ScalaJSModule linking: Mill's Scala.js worker is compiled against mainline Scala.js,
  * whose linker interface has no `ModuleKind.WasmComponent` (it exists only in the scala-wasm fork), so Mill cannot
  * ask any linker for a component. The driver is compiled against the fork's linker interface and calls the linker
  * through its public API (`StandardImpl.linker(config).link(...)`), exactly what sbt-scalajs's `fastLinkJS` /
  * `fullLinkJS` did for the fork's sbt plugin (same config: `WasmComponent`, ES2022, Wasm; full = optimized
  * semantics + minify + IR checking). RETIRES when Mill's ScalaJSModule can use a custom linker organization and
  * knows `ModuleKind.WasmComponent` (upstream Mill PR, see AGENTS.md "Wasm component layer rules").
  *
  * Arguments: `fast|full <output directory> <classpath entry>...` (the first IR file per class name wins, so the
  * stdlib overlay comes first). Writes `<output directory>/main.wasm`.
  */
object WasmLink {
  def main(args: Array[String]): Unit = {
    val full = args(0) match {
      case "full" => true
      case "fast" => false
      case other => sys.error(s"WasmLink: expected fast|full, got $other")
    }
    val out = Paths.get(args(1))
    val classpath: Seq[Path] = args.drop(2).toSeq.map(Paths.get(_))
    implicit val ec: ExecutionContext = ExecutionContext.global

    val base = StandardConfig()
      .withModuleKind(ModuleKind.WasmComponent)
      .withESFeatures(_.withUseWebAssembly(true).withESVersion(ESVersion.ES2022))
    val config =
      if (full) base.withSemantics(_.optimized).withMinify(true).withCheckIR(true)
      else base

    val cache = StandardImpl.irFileCache().newCache
    val logger = new ScalaConsoleLogger(Level.Info)
    val linking = for {
      (containers, _) <- PathIRContainer.fromClasspath(classpath)
      irFiles <- cache.cached(containers)
      report <- StandardImpl.linker(config).link(irFiles, Nil, PathOutputDirectory(out), logger)
    } yield report
    Await.result(linking, Duration.Inf)
  }
}
