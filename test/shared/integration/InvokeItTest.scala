package dapr4s.test.integration

import munit.FunSuite

/** [[dapr4s.InvokeCapability]] integration suite — a SINGLE cross-platform entry point. Registrations + scenarios come
  * from the shared [[InvokeSuiteDef]]; the bring-up and the [[InvokeScenarios]] hooks (`serverAppId` / `retrying` /
  * `withDapr`) come from `InvokeHarness`, a trait with one implementation per platform under the same name (a two-phase
  * host-server + testcontainers sidecar on the JVM; the in-process union server on Scala.js). Each platform build links
  * its own `InvokeHarness`, so this one file is the suite on both.
  */
@scala.caps.assumeSafe
class InvokeItTest extends FunSuite, InvokeHarness, InvokeSuiteDef
