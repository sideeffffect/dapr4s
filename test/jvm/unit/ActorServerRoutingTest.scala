//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.CounterActorApp
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM-only structural checks for the [[dapr4s.internal.DaprAppServer]] actor HTTP dispatch layer, hosting
  * [[CounterActorApp]] directly over HTTP (no sidecar, no Docker) — the residue of the former
  * `ActorCapabilityServerTest` that asserts the server's own routing/status codes rather than the [[ActorCapability]]
  * client path (which is covered cross-platform by `ActorItTest`). These cases (unknown route → 404, the `/dapr/config`
  * registration response, DELETE deactivation) cannot be exercised through a real sidecar, which only ever issues
  * valid, known requests.
  */
@scala.caps.assumeSafe
class ActorServerRoutingTest extends FunSuite, DaprServerTestBase:

  private def uniqueActorId() = s"actor-${java.util.UUID.randomUUID()}"

  test("actor: unknown actor type returns 404"):
    withServer(CounterActorApp()) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/actors/Nonexistent/1/method/get", "null")
      assertEquals(code, 404)
    }

  test("actor: unknown method returns 404"):
    withServer(CounterActorApp()) { port =>
      val id = uniqueActorId()
      val (code, _) = httpPostWithCode(s"http://localhost:$port/actors/Counter/$id/method/no-such", "null")
      assertEquals(code, 404)
    }

  test("actor: /dapr/config lists the Counter actor type"):
    withServer(CounterActorApp()) { port =>
      val resp = httpGet(s"http://localhost:$port/dapr/config")
      assert(resp.contains("Counter"), s"Expected Counter in config response: $resp")
    }

  test("actor: DELETE deactivation returns 200"):
    withServer(CounterActorApp()) { port =>
      val id = uniqueActorId()
      assertEquals(httpDeleteCode(s"http://localhost:$port/actors/Counter/$id"), 200)
    }

  test("actor: DaprApp ++ merges actor definitions"):
    val combined = CounterActorApp() ++ DaprApp(actors = List(ActorDefinition(ActorType("Other")) { _ =>
      ActorRoutes()
    }))
    assertEquals(combined.actors.size, 2)
    assert(combined.actors.exists(_.actorType.value == "Counter"))
    assert(combined.actors.exists(_.actorType.value == "Other"))

  private def httpDeleteCode(url: String): Int =
    val conn = java.net.URI(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
    conn.setRequestMethod("DELETE")
    conn.connect()
    val code = conn.getResponseCode
    conn.disconnect()
    code
