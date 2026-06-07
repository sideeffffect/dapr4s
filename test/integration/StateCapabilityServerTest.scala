package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Tests for every [[StateCapability]] method through real [[dapr4s.internal.DaprAppServer]] HTTP dispatch, backed by a
  * real Dapr in-memory state store via Testcontainers.
  *
  * Each test wraps its state operations in an [[InvocationRoute]] handler, starts the HTTP server, POSTs a request, and
  * asserts on the JSON response — the same path a real Dapr client would take.
  */
@scala.caps.assumeSafe
class StateCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("state-server-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap())),
    )
    c.start()
    c

  private def uniqueKey() = s"k-${java.util.UUID.randomUUID()}"

  // ---- get / save -----------------------------------------------------------

  test("state: save then get returns saved value"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("save")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](InvocationMethodName("get")) { _ =>
                  try StateCapability.get[String](StateStoreKey(k))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/save", "\"hello\"")
            val resp = httpPost(s"http://localhost:$port/get", "null")
            assert(resp.contains("hello"), s"Expected hello, got: $resp")
          }
        }
    }

  test("state: get missing key returns null"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, Option[String]](InvocationMethodName("get")) { _ =>
                  try StateCapability.get[String](StateStoreKey(s"absent-${java.util.UUID.randomUUID()}"))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            assertEquals(httpPost(s"http://localhost:$port/get", "null"), "null")
          }
        }
    }

  // ---- getWithETag ----------------------------------------------------------

  test("state: getWithETag returns value and etag after save"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("save")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](InvocationMethodName("get-with-etag")) { _ =>
                  try
                    val e = StateCapability.getWithETag[String](StateStoreKey(k))
                    s"${e.value.getOrElse("none")}|${e.etag.map(_.value).getOrElse("none")}"
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/save", "\"world\"")
            val resp = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/get-with-etag", "null"))
            val parts = resp.split("\\|", 2)
            assertEquals(parts(0), "world")
            assert(parts(1) != "none", "ETag should be present after save")
          }
        }
    }

  test("state: getWithETag for missing key returns none|none"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, String](InvocationMethodName("get-with-etag")) { _ =>
                  try
                    val e = StateCapability.getWithETag[String](StateStoreKey(s"absent-${java.util.UUID.randomUUID()}"))
                    s"${e.value.getOrElse("none")}|${e.etag.map(_.value).getOrElse("none")}"
                  catch case ex: Exception => throw ex
                },
              ),
            ),
          ) { port =>
            val resp = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/get-with-etag", "null"))
            assertEquals(resp, "none|none")
          }
        }
    }

  // ---- getBulk --------------------------------------------------------------

  test("state: getBulk returns Some for present keys and None for absent"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ka = uniqueKey()
        val kb = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          StateCapability.save(StateStoreKey(ka), "alpha")
          StateCapability.save(StateStoreKey(kb), "beta")
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[List[String], List[Option[String]]](InvocationMethodName("bulk-get")) { keys =>
                  try
                    val results = StateCapability.getBulk[String](keys.map(StateStoreKey(_)))
                    keys.map(k => results.get(StateStoreKey(k)).flatMap(_.value))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val resp =
              httpPost(s"http://localhost:$port/bulk-get", s"""["$ka","$kb","absent-${java.util.UUID.randomUUID()}"]""")
            val list = JsonCodec.decodeOrThrow[List[Option[String]]](resp)
            assertEquals(list, List(Some("alpha"), Some("beta"), None))
          }
        }
    }

  // ---- saveBulk -------------------------------------------------------------

  test("state: saveBulk persists all entries"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k1 = uniqueKey()
        val k2 = uniqueKey()
        val k3 = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, String](InvocationMethodName("save-bulk")) { _ =>
                  try
                    StateCapability.saveBulk[String](
                      List(StateStoreKey(k1) -> "v1", StateStoreKey(k2) -> "v2", StateStoreKey(k3) -> "v3"),
                    )
                    "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[String, Option[String]](InvocationMethodName("get")) { k =>
                  try StateCapability.get[String](StateStoreKey(k))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/save-bulk", "null")
            val r1 = JsonCodec.decodeOrThrow[Option[String]](httpPost(s"http://localhost:$port/get", s""""$k1""""))
            val r2 = JsonCodec.decodeOrThrow[Option[String]](httpPost(s"http://localhost:$port/get", s""""$k2""""))
            val r3 = JsonCodec.decodeOrThrow[Option[String]](httpPost(s"http://localhost:$port/get", s""""$k3""""))
            assertEquals(r1, Some("v1"))
            assertEquals(r2, Some("v2"))
            assertEquals(r3, Some("v3"))
          }
        }
    }

  // ---- saveWithETag ---------------------------------------------------------

  test("state: saveWithETag with correct etag succeeds"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("seed")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](InvocationMethodName("get-etag")) { _ =>
                  try StateCapability.getWithETag[String](StateStoreKey(k)).etag.map(_.value).getOrElse("none")
                  catch case ex: Exception => throw ex
                },
                InvocationRoute[String, String](InvocationMethodName("save-with-etag")) { etag =>
                  try
                    val err = StateCapability.saveWithETag(StateStoreKey(k), "new-value", ETag(etag))
                    if err.isDefined then "conflict" else "ok"
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/seed", "\"v1\"")
            val etag = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/get-etag", "null"))
            val result = JsonCodec.decodeOrThrow[String](
              httpPost(s"http://localhost:$port/save-with-etag", s""""$etag""""),
            )
            assertEquals(result, "ok")
          }
        }
    }

  test("state: saveWithETag with wrong etag returns conflict"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("seed")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](InvocationMethodName("save-with-wrong-etag")) { _ =>
                  try
                    val err = StateCapability.saveWithETag(StateStoreKey(k), "new", ETag("wrong-etag-999"))
                    if err.isDefined then "conflict" else "ok"
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/seed", "\"v1\"")
            val result = JsonCodec.decodeOrThrow[String](
              httpPost(s"http://localhost:$port/save-with-wrong-etag", "null"),
            )
            assertEquals(result, "conflict")
          }
        }
    }

  // ---- delete / deleteWithETag ----------------------------------------------

  test("state: delete removes a key"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("save")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](InvocationMethodName("delete")) { _ =>
                  try { StateCapability.delete(StateStoreKey(k)); "deleted" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](InvocationMethodName("get")) { _ =>
                  try StateCapability.get[String](StateStoreKey(k))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/save", "\"bye\"")
            httpPost(s"http://localhost:$port/delete", "null")
            assertEquals(httpPost(s"http://localhost:$port/get", "null"), "null")
          }
        }
    }

  test("state: deleteWithETag with correct etag removes key"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("seed")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](InvocationMethodName("get-etag")) { _ =>
                  try StateCapability.getWithETag[String](StateStoreKey(k)).etag.map(_.value).getOrElse("none")
                  catch case ex: Exception => throw ex
                },
                InvocationRoute[String, String](InvocationMethodName("del-with-etag")) { etag =>
                  try
                    val err = StateCapability.deleteWithETag(StateStoreKey(k), ETag(etag))
                    if err.isDefined then "conflict" else "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](InvocationMethodName("get")) { _ =>
                  try StateCapability.get[String](StateStoreKey(k))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/seed", "\"x\"")
            val etag = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/get-etag", "null"))
            val r = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/del-with-etag", s""""$etag""""))
            assertEquals(r, "ok")
            assertEquals(httpPost(s"http://localhost:$port/get", "null"), "null")
          }
        }
    }

  test("state: deleteWithETag with wrong etag leaves key"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("seed")) { v =>
                  try { StateCapability.save(StateStoreKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](InvocationMethodName("del-wrong")) { _ =>
                  try
                    val err = StateCapability.deleteWithETag(StateStoreKey(k), ETag("bad-etag"))
                    if err.isDefined then "conflict" else "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](InvocationMethodName("get")) { _ =>
                  try StateCapability.get[String](StateStoreKey(k))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/seed", "\"stay\"")
            val r = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/del-wrong", "null"))
            assertEquals(r, "conflict")
            assert(httpPost(s"http://localhost:$port/get", "null").contains("stay"))
          }
        }
    }

  // ---- transaction ----------------------------------------------------------

  test("state: transaction upserts and deletes atomically"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val kAdd = uniqueKey()
        val kDel = uniqueKey()
        DaprCapability.state(StateStoreName("statestore")) {
          StateCapability.save(StateStoreKey(kDel), "gone")
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, String](InvocationMethodName("tx")) { _ =>
                  try
                    StateCapability.transaction(
                      Seq(
                        StateOp.UpsertOp[String](StateStoreKey(kAdd), "new"),
                        StateOp.DeleteOp(StateStoreKey(kDel)),
                      ),
                    )
                    "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[String, Option[String]](InvocationMethodName("get")) { k =>
                  try StateCapability.get[String](StateStoreKey(k))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/tx", "null")
            assert(httpPost(s"http://localhost:$port/get", s""""$kAdd"""").contains("new"))
            assertEquals(httpPost(s"http://localhost:$port/get", s""""$kDel""""), "null")
          }
        }
    }
