package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite

@scala.caps.assumeSafe
class InvokeDerivationTest extends FunSuite:

  test("derived body method forwards appId, verbatim name, encoded body, default knobs"):
    val rec = RecordingInvoker("pong")
    given AccessInvokeCapability = rec
    val svc = Invoke.derive[Greeter](AppId("greeting-service"))
    val out = svc.double(Req(5))
    assertEquals(out, Resp("pong"))
    assertEquals(rec.calls.toList, List("body|greeting-service|double|5|Post|0"))

  test("derived body method forwards explicit httpMethod and metadata"):
    val rec = RecordingInvoker("ok")
    given AccessInvokeCapability = rec
    val svc = Invoke.derive[Greeter](AppId("greeting-service"))
    svc.double(Req(7), HttpMethod.Put, Map(MetadataKey("k") -> MetadataValue("v")))
    assertEquals(rec.calls.toList, List("body|greeting-service|double|7|Put|1"))

  test("derived body method with no knobs uses defaults"):
    val rec = RecordingInvoker("x")
    given AccessInvokeCapability = rec
    val svc = Invoke.derive[Greeter](AppId("greeting-service"))
    svc.plain(Req(1))
    assertEquals(rec.calls.toList, List("body|greeting-service|plain|1|Post|0"))

  test("derived no-body method uses the no-body overload and @name override"):
    val rec = RecordingInvoker("snapshot")
    given AccessInvokeCapability = rec
    val svc = Invoke.derive[Greeter](AppId("greeting-service"))
    val out = svc.stats()
    assertEquals(out, Resp("snapshot"))
    assertEquals(rec.calls.toList, List("nobody|greeting-service|get-stats"))

  test("derived method where Req == Resp resolves a single codec"):
    val rec = RecordingInvoker("echoed")
    given AccessInvokeCapability = rec
    val svc = Invoke.derive[Greeter](AppId("greeting-service"))
    val out = svc.echo(Resp("hi"))
    assertEquals(out, Resp("echoed"))
    assertEquals(rec.calls.toList, List("body|greeting-service|echo|hi|Post|0"))

  test("Invoke derive exposes a factory function"):
    val rec = RecordingInvoker("pong")
    given AccessInvokeCapability = rec
    val svc = MixinGreeter(AppId("mixin-service"))
    assertEquals(svc.plain(Req(3)), Resp("pong"))
    assertEquals(svc.stats(), Resp("pong"))
    assertEquals(
      rec.calls.toList,
      List("body|mixin-service|plain|3|Post|0", "nobody|mixin-service|get-stats"),
    )

  test("derive without appId routes to the trait's simple name"):
    val rec = RecordingInvoker("pong")
    given AccessInvokeCapability = rec
    val svc = Invoke.derive[Greeter]
    svc.double(Req(5))
    assertEquals(rec.calls.toList, List("body|Greeter|double|5|Post|0"))
