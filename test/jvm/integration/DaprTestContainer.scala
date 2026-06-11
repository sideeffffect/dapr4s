//> using target.platform "jvm"
package dapr4s.test.integration

import com.dimafeng.testcontainers.SingleContainer
import io.dapr.testcontainers.{DaprContainer => JDaprContainer}
import java.net.URI

/** Thin bridge so [[JDaprContainer]] plugs into testcontainers-scala-munit's [[com.dimafeng.testcontainers.Container]]
  * lifecycle API.
  *
  * `TestContainersForAll` expects `startContainers()` to return an already-started container. Call `c.start()` before
  * returning from `startContainers()`. `SingleContainer` delegates `stop()` to the underlying [[JDaprContainer]].
  */
final class DaprTestContainer(override val container: JDaprContainer) extends SingleContainer[JDaprContainer]:
  def httpEndpoint: URI = URI.create(s"http://${container.getHost}:${container.getHttpPort}")
  def grpcEndpoint: URI = URI.create(s"http://${container.getHost}:${container.getGrpcPort}")

object DaprTestContainer:
  val DefaultImage = "daprio/daprd:1.17.0"

  def apply(container: JDaprContainer): DaprTestContainer =
    container.waitingFor(
      org.testcontainers.containers.wait.strategy.Wait
        .forHttp("/v1.0/healthz")
        .forPort(3500)
        .forStatusCode(204),
    )
    new DaprTestContainer(container)
