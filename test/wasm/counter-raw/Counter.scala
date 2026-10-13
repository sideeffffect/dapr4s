package dapr4s.test.wasm.raw

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wit

import dapr4swit.dapr_wasm_components.interfaces.{invocation, state, state_read}
import dapr4swit.exports.dapr_wasm_components.interfaces.invocation_callback

/** The counter directly over the WIT bindings, without dapr4s's API: the baseline that isolates the toolchain from the
  * dapr4s wall. Run by scripts/wasm/e2e.sh (expects 1, then 2).
  *
  * It calls `state_read.get` / `state.save` exactly as wit-bindgen-scala documents, from Scala 3: those names are the
  * generated same-name facade (wasm/project/WitGlue.scala), whose bodies the WIT-aware 2.13 compiler lowered.
  */
object Counter:
  val StoreName = "statestore"
  val Key = "counter"

  private val noMetadata: Array[wit.Tuple2[String, String]] = Array()

  def readCount(): Int =
    state_read
      .get(StoreName, Key, wit.None, noMetadata)
      .fold(
        ok => parseCount(ok.data),
        {
          case state_read.GetError.KeyNotFound => 0
          case other                           => throw new IllegalStateException(s"state get failed: $other")
        },
      )

  private def parseCount(data: Array[Byte]): Int =
    // The wasi-http provider stores JSON; a number round-trips as its decimal text.
    val text = new String(data, UTF_8).trim.stripPrefix("\"").stripSuffix("\"")
    if text.isEmpty then 0 else text.toInt

  def increment(): Int =
    val next = readCount() + 1
    val item = state_read.StateItem(Key, next.toString.getBytes(UTF_8), wit.None, noMetadata, wit.None)
    state
      .save(StoreName, Array(item), noMetadata)
      .fold(_ => next, err => throw new IllegalStateException(s"state save failed: $err"))

object CounterHandler extends invocation_callback.InvocationCallbackExports:
  override def onInvoke(request: invocation_callback.InvokeRequest): invocation.HttpResponse =
    request.method match
      case "counter" =>
        val n = Counter.increment()
        invocation.HttpResponse(
          200.toShort,
          Array(wit.Tuple2("content-type", "text/plain")),
          n.toString.getBytes(UTF_8),
        )
      case other =>
        invocation.HttpResponse(404.toShort, Array(), s"no method $other".getBytes(UTF_8))
