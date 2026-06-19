package dapr4s.test.unit

import dapr4s.*
import dapr4s.derivation.*
import scala.collection.mutable

// Fixtures for InvokeDerivationTest. Kept in their own file so the
// top-level `given`/`trait`/`class` definitions do not interfere with munit's
// reflective instantiation of the test class.

final case class Req(n: Int)
final case class Resp(s: String)

@scala.caps.assumeSafe
given JsonCodec[Req] with
  def encode(value: Req): String = value.n.toString
  def decode(json: String | Null): Either[JsonDecodeException, Req] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(Req(json.trim.toInt))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Resp] with
  // raw passthrough, so the recording fake can return any string
  def encode(value: Resp): String = value.s
  def decode(json: String | Null): Either[JsonDecodeException, Resp] =
    if json == null then Left(JsonDecodeException("null input")) else Right(Resp(json))

/** Trait describing remote calls; implemented by [[dapr4s.derivation.Invoke.derive]]. */
trait Greeter:
  // body-bearing, with the optional knobs declared
  def double(
      req: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using AccessInvokeCapability, JsonCodec[Req], JsonCodec[Resp]): Resp

  // body-bearing, no knobs at all
  def plain(req: Req)(using AccessInvokeCapability, JsonCodec[Req], JsonCodec[Resp]): Resp

  // no-body, with a wire-name override
  @name("get-stats")
  def stats()(using AccessInvokeCapability, JsonCodec[Resp]): Resp

  // Req and Resp are the same type — exercises the single-codec branch
  def echo(req: Resp)(using AccessInvokeCapability, JsonCodec[Resp]): Resp

/** Same shape as [[Greeter]] but exposed through a `MixinGreeter(appId)` factory built on
  * [[dapr4s.derivation.Invoke.derive]].
  */
trait MixinGreeter:
  def plain(req: Req)(using AccessInvokeCapability, JsonCodec[Req], JsonCodec[Resp]): Resp

  @name("get-stats")
  def stats()(using AccessInvokeCapability, JsonCodec[Resp]): Resp

def MixinGreeter(appId: AppId): MixinGreeter = Invoke.derive[MixinGreeter](appId)

/** Recording fake accessor: narrows to a [[RecordingInvoke]] bound to the requested app, which logs each call into the
  * shared buffer (the app id is recorded so the existing assertions still see it).
  */
@scala.caps.assumeSafe
final class RecordingInvoker(response: String) extends AccessInvokeCapability:
  val calls: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  def apply(appId: AppId): InvokeCapability^{this} =
    new RecordingInvoke(appId, response, calls).asInstanceOf[InvokeCapability]

/** Recording fake capability bound to one app: logs each call and returns a fixed response payload. */
@scala.caps.assumeSafe
final class RecordingInvoke(val appId: AppId, response: String, calls: mutable.ListBuffer[String])
    extends InvokeCapability:

  def invoke[Req: JsonCodec](
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod,
      metadata: Map[MetadataKey, MetadataValue],
  )[Resp: JsonCodec]: Resp =
    calls += s"body|${appId.value}|${method.value}|${summon[JsonCodec[Req]].encode(data)}|$httpMethod|${metadata.size}"
    JsonCodec.decodeOrThrow[Resp](response)

  def invoke[Resp: JsonCodec](method: InvokeMethodName): Resp =
    calls += s"nobody|${appId.value}|${method.value}"
    JsonCodec.decodeOrThrow[Resp](response)
