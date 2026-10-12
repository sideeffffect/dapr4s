//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import dapr4s.internal.DaprAppServer
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Unit tests for job-trigger dispatch in [[DaprAppServer]].
  *
  * The Dapr sidecar fires a scheduled job by POSTing to `/job/{name}` on the app's HTTP server. These tests POST
  * directly to a running [[DaprAppServer]] without a real sidecar or scheduler — exercising the [[JobRoute]] dispatch
  * logic in isolation. Both the raw-payload and `{"data":...}`-envelope delivery shapes are covered.
  */
@scala.caps.assumeSafe
class JobDispatchTest extends FunSuite with DaprServerTestBase:

  test("job dispatch: known job name invokes handler and returns 200"):
    var invoked = false
    val app = DaprApp(
      jobs = List(
        JobRoute[String](JobName("reminder")) { _ => invoked = true },
      ),
    )
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/job/reminder", "\"payload\"")
      assertEquals(code, 200)
      assert(invoked, "job handler should have been invoked")
    }

  test("job dispatch: unknown job name returns 404"):
    val app = DaprApp(
      jobs = List(
        JobRoute[String](JobName("known-job")) { _ => () },
      ),
    )
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/job/unknown-job", "\"data\"")
      assertEquals(code, 404)
    }

  test("job dispatch: handler receives raw payload"):
    var received: String | Null = null
    val app = DaprApp(
      jobs = List(
        JobRoute[String](JobName("echo")) { payload => received = payload },
      ),
    )
    withServer(app) { port =>
      httpPostWithCode(s"http://localhost:$port/job/echo", "\"hello-from-scheduler\"")
      assertEquals(received, "hello-from-scheduler")
    }

  test("job dispatch: handler receives payload from {data} envelope"):
    var received = -1
    val app = DaprApp(
      jobs = List(
        JobRoute[Int](JobName("counter")) { n => received = n },
      ),
    )
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/job/counter", """{"data":7}""")
      assertEquals(code, 200)
      assertEquals(received, 7)
    }

  test("job dispatch: handler exception returns 500"):
    val app = DaprApp(
      jobs = List(
        JobRoute[String](JobName("failing-job")) { _ =>
          throw RuntimeException("deliberate job failure")
        },
      ),
    )
    withServer(app) { port =>
      val (code, body) = httpPostWithCode(s"http://localhost:$port/job/failing-job", "\"data\"")
      assertEquals(code, 500)
      assert(body.contains("RuntimeException"), s"expected error body, got: $body")
    }

  test("job dispatch: DaprApp with no jobs returns 404 for job path"):
    val app = DaprApp()
    withServer(app) { port =>
      val (code, _) = httpPostWithCode(s"http://localhost:$port/job/some-job", "\"data\"")
      assertEquals(code, 404)
    }
