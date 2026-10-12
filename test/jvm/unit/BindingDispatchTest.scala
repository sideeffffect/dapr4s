//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import dapr4s.internal.DaprAppServer
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Unit tests for input-binding dispatch in [[DaprAppServer]].
  *
  * The Dapr sidecar fires binding triggers by POSTing to `/{bindingName}` on the app's HTTP server. These tests POST
  * directly to a running [[DaprAppServer]] without a real sidecar or Docker — exercising the binding dispatch logic in
  * isolation.
  *
  * Complements [[SubscriberTest]], which also covers pub/sub and invocation dispatch, by providing a focused,
  * well-named suite for binding-specific behaviour.
  */
@scala.caps.assumeSafe
class BindingDispatchTest extends FunSuite with DaprServerTestBase:

  test("binding dispatch: known binding name invokes handler and returns 200"):
    var invoked = false
    val app = DaprApp(
      bindings = List(
        BindingRoute[String](BindingName("test-binding")) { _ => invoked = true },
      ),
    )
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/test-binding", "\"payload\"")
      assertEquals(code, 200)
      assert(invoked, "binding handler should have been invoked")
    }

  test("binding dispatch: unknown binding name returns 404"):
    val app = DaprApp(
      bindings = List(
        BindingRoute[String](BindingName("known-binding")) { _ => () },
      ),
    )
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/unknown-binding", "\"data\"")
      assertEquals(code, 404)
    }

  test("binding dispatch: handler receives correct payload"):
    var received: String | Null = null
    val app = DaprApp(
      bindings = List(
        BindingRoute[String](BindingName("test-binding")) { payload => received = payload },
      ),
    )
    withServer(app) { port =>
      httpPostWithCode(s"http://localhost:$port/test-binding", "\"hello-from-dapr\"")
      assertEquals(received, "hello-from-dapr")
    }

  test("binding dispatch: handler receives structured payload"):
    var receivedCount = -1
    val app = DaprApp(
      bindings = List(
        BindingRoute[Int](BindingName("counter-binding")) { n => receivedCount = n },
      ),
    )
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/counter-binding", "42")
      assertEquals(code, 200)
      assertEquals(receivedCount, 42)
    }

  test("binding dispatch: handler exception returns 500"):
    val app = DaprApp(
      bindings = List(
        BindingRoute[String](BindingName("failing-binding")) { _ =>
          throw RuntimeException("deliberate binding failure")
        },
      ),
    )
    withServer(app) { port =>
      val (code, body) = httpPostWithCode(s"http://localhost:$port/failing-binding", "\"data\"")
      assertEquals(code, 500)
      assert(body.contains("RuntimeException"), s"expected error body, got: $body")
    }

  test("binding dispatch: DaprApp with no bindings returns 404 for binding path"):
    val app = DaprApp()
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/some-binding", "\"data\"")
      assertEquals(code, 404)
    }

  test("binding dispatch: multiple bindings are dispatched to the correct handler"):
    var receivedA: String | Null = null
    var receivedB: String | Null = null
    val app = DaprApp(
      bindings = List(
        BindingRoute[String](BindingName("binding-a")) { s => receivedA = s },
        BindingRoute[String](BindingName("binding-b")) { s => receivedB = s },
      ),
    )
    withServer(app) { port =>
      httpPostWithCode(s"http://localhost:$port/binding-a", "\"msg-a\"")
      httpPostWithCode(s"http://localhost:$port/binding-b", "\"msg-b\"")
      assertEquals(receivedA, "msg-a")
      assertEquals(receivedB, "msg-b")
    }
