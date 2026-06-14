package dapr4s.test.integration

import dapr4s.*

/** The abstract bring-up boundary the shared `*SuiteDef` registration traits call.
  *
  * The JVM fixtures ([[SharedDaprItSuite]] and the server-delivery suites) run `body` synchronously and return `Unit`;
  * the JS fixtures ([[SharedDaprJsItSuite]] / [[ServerDaprJsItSuite]]) return a `Future[Unit]` from a
  * `js.async{}.toFuture` boundary. Both are `<: Any`, which munit's `test(name)(body: => Any)` accepts (it awaits a
  * returned `Future` via `munitValueTransform`) — so a `*SuiteDef` can register the identical tests against this one
  * signature on both platforms, and the per-platform class supplies only the bring-up.
  */
@scala.caps.assumeSafe
trait DaprItFixture:
  def withDapr(body: DaprCapability ?=> Unit): Any
