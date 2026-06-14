//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js

/** Scala.js implementation of the service-suite [[ServiceHarnessApi]] (the JVM twin lives in test/jvm). All service
  * suites share ONE [[DaprJsIt.serviceStack]] — a sidecar (no app channel) plus a `serveAsync` server hosting
  * `OrderServiceApp ++ InventoryServiceApp` — which the tests poke DIRECTLY over `fetch` (invoke routes and the
  * subscription CloudEvent POST). `appOf` is therefore ignored here (the shared server already hosts every service
  * route; their method/topic names are disjoint); only the JVM consults it to host a fresh per-test server.
  */
@scala.caps.assumeSafe
trait ServiceHarness extends ServiceHarnessApi:
  self: FunSuite =>

  override def munitTimeout: Duration = 120.seconds

  private var portRef: Int = -1

  override protected def invokeRaw(path: String, reqBody: String): (Int, String) =
    DaprJsIt.httpPostWithCode(s"http://localhost:$portRef/$path", reqBody)

  override protected def withService(appOf: DaprCapability ?=> DaprApp)(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async {
      val (cfg, port) = DaprJsIt.serviceStack()
      portRef = port
      Dapr(cfg).run:
        body(using summon[DaprCapability])
    }.toFuture
