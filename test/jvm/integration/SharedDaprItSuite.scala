//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.lifecycle.Andable.AndableOps
import com.dimafeng.testcontainers.munit.TestContainersForAll
import io.dapr.testcontainers.DaprContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import munit.FunSuite

/** JVM integration fixture that mirrors the JS (Wasm+JSPI) harness's single all-components sidecar: one daprd backed by
  * a real Redis, loading the CANONICAL shared component set (scripts/it/components/<name>.yaml) via
  * [[DaprContainer.withComponent(Path)]], with the shared `secrets.json` and a fresh RSA key mounted at `/dapr4s-it`.
  *
  * Direct-call capability suites mix this in and call the shared scenario traits in `test/shared/scenarios`, so the JVM
  * and JS suites exercise the exact same calls and assertions against the exact same component definitions — only
  * bring-up (testcontainers here, external Docker+Node there) and the munit boundary (synchronous here, `Future` there)
  * differ.
  */
trait SharedDaprItSuite extends TestContainersForAll:
  self: FunSuite =>

  override type Containers = GenericContainer and DaprTestContainer

  // Canonical component names live once in ItNames (= scripts/it/components/<name>.yaml).

  override def startContainers(): GenericContainer and DaprTestContainer =
    val network = Network.newNetwork()

    val redis = GenericContainer(
      dockerImage = "redis:7-alpine",
      exposedPorts = Seq(6379),
      waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
    )
    redis.container.withNetwork(network)
    redis.container.withNetworkAliases(JvmItComponents.RedisAlias)
    redis.start()

    // Seed configuration items before daprd reads them (redis config store splits "value||version").
    val args = Array("redis-cli", "MSET") ++ JvmItComponents.SeededConfig.flatMap((k, v) => List(k, v))
    val seed = redis.container.execInContainer(args*)
    assertEquals(seed.getExitCode, 0, s"redis MSET failed: ${seed.getStderr}")

    val res = JvmItComponents.render()

    var dc = DaprContainer(DaprTestContainer.DefaultImage)
      .withNetwork(network)
      .withAppName("shared-it")
      .withAppPort(0)
      // 0x1ed = 0755 (keys dir + key), 0x1a4 = 0644 (secrets file): daprd runs as non-root and
      // otherwise fails the components with "permission denied".
      .withCopyFileToContainer(MountableFile.forHostPath(res.keysDir, 0x1ed), "/dapr4s-it/keys")
      .withCopyFileToContainer(MountableFile.forHostPath(res.secretsFile, 0x1a4), "/dapr4s-it/secrets.json")
      .dependsOn(redis.container)
    for p <- res.components.values do dc = dc.withComponent(p)

    val c = DaprTestContainer(dc)
    c.start()
    redis and c

  /** Run `body` against the started sidecar with a [[DaprCapability]] in scope — the JVM analogue of the JS suites'
    * `Dapr(clientConfig).run { ... }`.
    */
  protected def withDapr(body: DaprCapability ?=> Unit): Unit =
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint)(body)
    }
