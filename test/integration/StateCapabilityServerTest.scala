package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Tests for every [[StateCapability]] method through real [[dapr.safe.internal.DaprAppServer]] HTTP dispatch, backed
  * by a real Dapr in-memory state store via Testcontainers.
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("save")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](MethodName("get")) { _ =>
                  try StateCapability.get[String](StateKey(k))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, Option[String]](MethodName("get")) { _ =>
                  try StateCapability.get[String](StateKey(s"absent-${java.util.UUID.randomUUID()}"))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("save")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](MethodName("get-with-etag")) { _ =>
                  try
                    val e = StateCapability.getWithETag[String](StateKey(k))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, String](MethodName("get-with-etag")) { _ =>
                  try
                    val e = StateCapability.getWithETag[String](StateKey(s"absent-${java.util.UUID.randomUUID()}"))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ka = uniqueKey()
        val kb = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          StateCapability.save(StateKey(ka), "alpha")
          StateCapability.save(StateKey(kb), "beta")
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[List[String], List[Option[String]]](MethodName("bulk-get")) { keys =>
                  try
                    val results = StateCapability.getBulk[String](keys.map(StateKey(_)))
                    keys.map(k => results.get(StateKey(k)).flatMap(_.value))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k1 = uniqueKey()
        val k2 = uniqueKey()
        val k3 = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, String](MethodName("save-bulk")) { _ =>
                  try
                    StateCapability.saveBulk[String](
                      List(StateKey(k1) -> "v1", StateKey(k2) -> "v2", StateKey(k3) -> "v3"),
                    )
                    "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[String, Option[String]](MethodName("get")) { k =>
                  try StateCapability.get[String](StateKey(k))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("seed")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](MethodName("get-etag")) { _ =>
                  try StateCapability.getWithETag[String](StateKey(k)).etag.map(_.value).getOrElse("none")
                  catch case ex: Exception => throw ex
                },
                InvocationRoute[String, String](MethodName("save-with-etag")) { etag =>
                  try
                    val err = StateCapability.saveWithETag(StateKey(k), "new-value", ETag(etag))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("seed")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](MethodName("save-with-wrong-etag")) { _ =>
                  try
                    val err = StateCapability.saveWithETag(StateKey(k), "new", ETag("wrong-etag-999"))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("save")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](MethodName("delete")) { _ =>
                  try { StateCapability.delete(StateKey(k)); "deleted" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](MethodName("get")) { _ =>
                  try StateCapability.get[String](StateKey(k))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("seed")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](MethodName("get-etag")) { _ =>
                  try StateCapability.getWithETag[String](StateKey(k)).etag.map(_.value).getOrElse("none")
                  catch case ex: Exception => throw ex
                },
                InvocationRoute[String, String](MethodName("del-with-etag")) { etag =>
                  try
                    val err = StateCapability.deleteWithETag(StateKey(k), ETag(etag))
                    if err.isDefined then "conflict" else "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](MethodName("get")) { _ =>
                  try StateCapability.get[String](StateKey(k))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val k = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](MethodName("seed")) { v =>
                  try { StateCapability.save(StateKey(k), v); "ok" }
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, String](MethodName("del-wrong")) { _ =>
                  try
                    val err = StateCapability.deleteWithETag(StateKey(k), ETag("bad-etag"))
                    if err.isDefined then "conflict" else "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[Unit, Option[String]](MethodName("get")) { _ =>
                  try StateCapability.get[String](StateKey(k))
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val kAdd = uniqueKey()
        val kDel = uniqueKey()
        DaprCapability.state(StoreName("statestore")) {
          StateCapability.save(StateKey(kDel), "gone")
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, String](MethodName("tx")) { _ =>
                  try
                    StateCapability.transaction(
                      Seq(
                        StateOp.UpsertOp[String](StateKey(kAdd), "new"),
                        StateOp.DeleteOp(StateKey(kDel)),
                      ),
                    )
                    "ok"
                  catch case e: Exception => throw e
                },
                InvocationRoute[String, Option[String]](MethodName("get")) { k =>
                  try StateCapability.get[String](StateKey(k))
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
