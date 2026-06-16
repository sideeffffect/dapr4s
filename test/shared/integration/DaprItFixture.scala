package dapr4s.test.integration

import dapr4s.*

/** The abstract bring-up boundary the shared `*SuiteDef` registration traits — and the shared concrete suite classes
  * (e.g. [[StateItTest]]) — depend on.
  *
  * `SharedDaprItSuite` (direct-call suites) and `ServerDaprItSuite` (server-delivery suites) are each defined once PER
  * PLATFORM under the same name (the standard Scala.js cross-build idiom; the two definitions never compile together
  * because each carries a `//> using target.platform` directive). The JVM implementation runs `body` synchronously and
  * returns `Unit`; the JS one returns a `Future[Unit]` from a `js.async{}.toFuture` boundary. Both are `<: Any`, which
  * munit's `test(name)(body: => Any)` accepts (it awaits a returned `Future` via `munitValueTransform`) — so the entire
  * suite (registrations AND the concrete class) lives in test/shared, and each platform build just links its own
  * bring-up.
  */
@scala.caps.assumeSafe
trait DaprItFixture:
  def withDapr(body: DaprCapability ?=> Unit): Any
