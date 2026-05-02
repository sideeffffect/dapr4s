package dapr.safe.test.integration

import com.dimafeng.testcontainers.SingleContainer
import io.dapr.testcontainers.{DaprContainer => JDaprContainer}

/** Thin bridge so [[JDaprContainer]] plugs into testcontainers-scala-munit's [[com.dimafeng.testcontainers.Container]]
  * lifecycle API.
  *
  * `TestContainersForAll` expects `startContainers()` to return an already-started container. Call `c.start()` before
  * returning from `startContainers()`. `SingleContainer` delegates `stop()` to the underlying [[JDaprContainer]].
  */
final class DaprTestContainer(override val container: JDaprContainer) extends SingleContainer[JDaprContainer]:
  def httpEndpoint: String = s"http://${container.getHost}:${container.getHttpPort}"
  def grpcEndpoint: String = s"http://${container.getHost}:${container.getGrpcPort}"

object DaprTestContainer:
  def apply(container: JDaprContainer): DaprTestContainer = new DaprTestContainer(container)
