//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite
import org.testcontainers.containers.Network
import unsafeExceptions.canThrowAny

/** Tests for every [[PublishCapability]] method through real [[dapr4s.internal.DaprAppServer]] HTTP dispatch, backed by
  * the canonical `pubsub.redis` + `state.redis` components (the shared scripts/it/components set, matching the JS
  * harness — see [[RedisFixture]]).
  *
  * Publish operations fire at the real Dapr sidecar and verify the handler returns without error. Subscription dispatch
  * is exercised by POSTing a CloudEvent JSON envelope directly to the subscription route — the same format Dapr uses in
  * production.
  */
@scala.caps.assumeSafe
class PublishCapabilityServerTest extends FunSuite, RedisFixture, DaprServerTestBase:

  override type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val network = Network.newNetwork()
    val res = startRedis(network)
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("pubsub-server-test")
        .withAppPort(0)
        .withComponent(res.component("pubsub"))
        .withComponent(res.component("statestore")),
    )
    c.start()
    c

  private def uniqueTopic() = s"topic-${java.util.UUID.randomUUID()}"

  // ---- publish ---------------------------------------------------------------

  test("pubsub: publish fires without error"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.publish(PubSubName("pubsub")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[String, String](InvokeMethodName("pub")) { msg =>
                  try { PublishCapability.publish(Topic("orders"), msg); "ok" }
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
        DaprCapability.publish(PubSubName("pubsub")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[String, String](InvokeMethodName("pub-meta")) { msg =>
                  try
                    PublishCapability.publishWithMetadata(
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
        DaprCapability.publish(PubSubName("pubsub")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[List[String], Int](InvokeMethodName("bulk")) { msgs =>
                  try
                    val entries = msgs.zipWithIndex.map { case (m, i) =>
                      BulkPublishEntry(BulkEntryId(i.toString), m)
                    }
                    val result = PublishCapability.bulkPublish(Topic("orders"), entries)
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
        DaprCapability.publish(PubSubName("pubsub")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[List[String], Int](InvokeMethodName("bulk")) { msgs =>
                  try
                    val result = PublishCapability
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
          DaprCapability.publish(PubSubName("pubsub")) {
            withServer(
              DaprApp(
                subscriptions = List(
                  Subscription[String](PubSubName("pubsub"), Topic(topic)) { event =>
                    try { StateCapability.save(StateStoreKey(stateKey), event.data); SubscriptionResult.Success }
                    catch case e: Exception => throw e
                  },
                ),
                invokeRoutes = List(
                  InvokeRoute[Unit, Option[String]](InvokeMethodName("get-recv")) { _ =>
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
        DaprCapability.publish(PubSubName("pubsub")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[String, String](InvokeMethodName("pub")) { msg =>
                  try { PublishCapability.publish(Topic("t"), msg); "ok" }
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
