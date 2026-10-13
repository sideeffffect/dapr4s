package dapr4s.test.wasm.raw

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wit

import dapr4swit.dapr_wasm_components.interfaces.{invocation, state_read}
import dapr4swit.exports.dapr_wasm_components.interfaces.invocation_callback
import dapr4s.internal.witglue.{InvocationHandler, StateForward}

/** The counter directly over the generated WIT bindings (via the 2.13 forwarders), without dapr4s's API: the baseline
  * that isolates the toolchain from the dapr4s wall. Run by scripts/wasm/e2e.sh (expects 1, then 2).
  */
object Counter:
  val StoreName = "statestore"
  val Key = "counter"

  private val noMetadata: Array[wit.Tuple2[String, String]] = Array()

  def readCount(): Int =
    StateForward
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
    StateForward
      .save(StoreName, Array(item), noMetadata)
      .fold(_ => next, err => throw new IllegalStateException(s"state save failed: $err"))

object CounterHandler extends InvocationHandler:
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
