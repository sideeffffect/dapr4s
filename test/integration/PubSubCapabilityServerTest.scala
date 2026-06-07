package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Tests for every [[PubSubCapability]] method through real [[dapr4s.internal.DaprAppServer]] HTTP dispatch, backed by
  * real Dapr pub/sub and state-store components via Testcontainers.
  *
  * Publish operations fire at the real Dapr sidecar and verify the handler returns without error. Subscription dispatch
  * is exercised by POSTing a CloudEvent JSON envelope directly to the subscription route — the same format Dapr uses in
  * production.
  */
@scala.caps.assumeSafe
class PubSubCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("pubsub-server-test")
        .withAppPort(0)
        .withComponent(Component("pubsub", "pubsub.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap())),
    )
    c.start()
    c

  private def uniqueTopic() = s"topic-${java.util.UUID.randomUUID()}"

  // ---- publish ---------------------------------------------------------------

  test("pubsub: publish fires without error"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.pubsub(PubSubName("pubsub")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("pub")) { msg =>
                  try { PubSubCapability.publish(Topic("orders"), msg); "ok" }
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val result = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/pub", "\"hello\""))
            assertEquals(result, "ok")
          }
        }
    }

  test("pubsub: publishWithMetadata fires without error"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.pubsub(PubSubName("pubsub")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("pub-meta")) { msg =>
                  try
                    PubSubCapability.publishWithMetadata(
                      Topic("orders"),
                      msg,
                      Map(MetadataKey("traceId") -> MetadataValue("abc123")),
                    )
                    "ok"
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val result = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/pub-meta", "\"event\""))
            assertEquals(result, "ok")
          }
        }
    }

  // ---- bulkPublish -----------------------------------------------------------

  test("pubsub: bulkPublish returns empty failedEntries"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.pubsub(PubSubName("pubsub")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[List[String], Int](InvocationMethodName("bulk")) { msgs =>
                  try
                    val entries = msgs.zipWithIndex.map { case (m, i) =>
                      BulkPublishEntry(BulkEntryId(i.toString), m)
                    }
                    val result = PubSubCapability.bulkPublish(Topic("orders"), entries)
                    result.failedEntries.length
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val resp = httpPost(s"http://localhost:$port/bulk", """["a","b","c"]""")
            assertEquals(JsonCodec.decodeOrThrow[Int](resp), 0)
          }
        }
    }

  test("pubsub: bulkPublish with empty list propagates SDK error"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.pubsub(PubSubName("pubsub")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[List[String], Int](InvocationMethodName("bulk")) { msgs =>
                  try
                    val result = PubSubCapability
                      .bulkPublish(Topic("orders"), msgs.map(m => BulkPublishEntry(BulkEntryId("0"), m)))
                    result.failedEntries.length
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val (code, body) = httpPostWithCode(s"http://localhost:$port/bulk", "[]")
            assertEquals(code, 500)
            val json = ujson.read(body)
            assertEquals(json("error").str, "IllegalArgumentException")
            assert(json("error_description").str.nonEmpty)
          }
        }
    }

  // ---- subscription dispatch -------------------------------------------------

  test("pubsub: subscription handler receives and processes event"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val topic = uniqueTopic()
        val stateKey = s"recv-${java.util.UUID.randomUUID()}"
        DaprCapability.state(StateStoreName("statestore")) {
          DaprCapability.pubsub(PubSubName("pubsub")) {
            withServer(
              DaprApp(
                subscriptions = List(
                  Subscription[String](PubSubName("pubsub"), Topic(topic)) { event =>
                    try { StateCapability.save(StateStoreKey(stateKey), event.data); SubscriptionResult.Success }
                    catch case e: Exception => throw e
                  },
                ),
                invocations = List(
                  InvocationRoute[Unit, Option[String]](InvocationMethodName("get-recv")) { _ =>
                    try StateCapability.get[String](StateStoreKey(stateKey))
                    catch case e: Exception => throw e
                  },
                ),
              ),
            ) { port =>
              deliverCloudEvent(port, topic, "pubsub", "payload-value")
              val result = JsonCodec.decodeOrThrow[Option[String]](
                httpPost(s"http://localhost:$port/get-recv", "null"),
              )
              assertEquals(result, Some("payload-value"))
            }
          }
        }
    }

  test("pubsub: multiple publishes all fire without error"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.pubsub(PubSubName("pubsub")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, String](InvocationMethodName("pub")) { msg =>
                  try { PubSubCapability.publish(Topic("t"), msg); "ok" }
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            for msg <- List("first", "second", "third") do
              assertEquals(
                JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/pub", s""""$msg"""")),
                "ok",
              )
          }
        }
    }
