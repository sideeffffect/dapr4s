package dapr4s.test.integration

import dapr4s.*
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Tests for every [[SecretsCapability]] method through real [[dapr4s.internal.DaprAppServer]] HTTP dispatch, backed by
  * a real `secretstores.local.env` component that reads from the Dapr container's environment variables.
  *
  * Two secrets are pre-seeded via `addEnv` when the container is created.
  */
@scala.caps.assumeSafe
class SecretsCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  private val SeededKey = "SSDAPR_TEST_SECRET_A"
  private val SeededValue = "secret-value-alpha"
  private val SeededKey2 = "SSDAPR_TEST_SECRET_B"
  private val SeededValue2 = "secret-value-beta"

  override def startContainers(): DaprTestContainer =
    val dc = DaprContainer(DaprTestContainer.DefaultImage)
      .withAppName("secrets-server-test")
      .withAppPort(0)
      .withComponent(Component("envstore", "secretstores.local.env", "v1", Collections.emptyMap()))
    dc.addEnv(SeededKey, SeededValue)
    dc.addEnv(SeededKey2, SeededValue2)
    val c = DaprTestContainer(dc)
    c.start()
    c

  // ---- get -------------------------------------------------------------------

  test("secrets: get returns seeded env var value"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.secrets(SecretStoreName("envstore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, Option[SecretValue]](MethodName("get")) { key =>
                  try SecretsCapability.get(SecretKey(key))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val resp = JsonCodec.decodeOrThrow[Option[SecretValue]](
              httpPost(s"http://localhost:$port/get", s""""$SeededKey""""),
            )
            assertEquals(resp, Some(SecretValue(SeededValue)))
          }
        }
    }

  test("secrets: get distinguishes between two seeded keys"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.secrets(SecretStoreName("envstore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[String, Option[SecretValue]](MethodName("get")) { key =>
                  try SecretsCapability.get(SecretKey(key))
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val r1 =
              JsonCodec.decodeOrThrow[Option[SecretValue]](httpPost(s"http://localhost:$port/get", s""""$SeededKey""""))
            val r2 =
              JsonCodec.decodeOrThrow[Option[SecretValue]](
                httpPost(s"http://localhost:$port/get", s""""$SeededKey2""""),
              )
            assertEquals(r1, Some(SecretValue(SeededValue)))
            assertEquals(r2, Some(SecretValue(SeededValue2)))
          }
        }
    }

  // ---- getBulk ---------------------------------------------------------------

  test("secrets: getBulk result contains seeded env var keys"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.secrets(SecretStoreName("envstore")) {
          withServer(
            DaprApp(invocations =
              List(
                InvocationRoute[Unit, Map[String, String]](MethodName("bulk")) { _ =>
                  try SecretsCapability.getBulk().map { case (k, v) => k.value -> v.value }
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val bulk = JsonCodec.decodeOrThrow[Map[String, String]](
              httpPost(s"http://localhost:$port/bulk", "null"),
            )
            // local.env getBulk returns keys in "NAME/NAME" format from the nested subKey structure
            assert(
              bulk.exists { case (k, v) => k.contains(SeededKey) && v == SeededValue },
              s"Expected $SeededKey=$SeededValue in bulk result; got keys: ${bulk.keys.filter(_.startsWith("SSDAPR")).toList}",
            )
            assert(
              bulk.exists { case (k, v) => k.contains(SeededKey2) && v == SeededValue2 },
              s"Expected $SeededKey2=$SeededValue2 in bulk result",
            )
          }
        }
    }
