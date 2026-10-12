//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*

import java.nio.file.{Files, Path, Paths}
import java.security.KeyPairGenerator
import java.util.Base64

/** Renders the CANONICAL shared Dapr component set (scripts/it/components/<name>.yaml) for the JVM testcontainers
  * topology into a fresh temp dir, alongside the shared secrets.json and a freshly generated RSA key — the exact same
  * inputs the JS harness assembles (scripts/js-integration-env.sh).
  *
  * The YAML files are the single source of truth; only `${DAPR4S_IT_REDIS_HOST}` is environment-specific. Here it is
  * substituted with `redis:6379` (the redis testcontainer's network alias on the shared Docker network) — mirroring
  * scripts/it/render-components.sh, which the JS host-network harness uses with `localhost:6391`.
  *
  * The rendered tree is then fed to a daprd container:
  *   - component YAMLs via `DaprContainer.withComponent(Path)`,
  *   - the `keys/` dir and `secrets.json` mounted at `/dapr4s-it` (the in-container paths the cryptostore/secretstore
  *     manifests reference).
  */
object JvmItComponents:

  // RedisAlias / RedisHostValue / Placeholder / ComponentFileNames / SeededConfig live once in the
  // cross-platform ItNames (= scripts/it/components/<name>.yaml); both component renderers reference them.

  /** A rendered resource tree: the temp root, the rendered component file Paths keyed by component name (e.g.
    * "statestore"), the keys dir and the secrets.json file (ready to mount into daprd).
    */
  final case class Rendered(root: Path, components: Map[String, Path], keysDir: Path, secretsFile: Path):
    /** The rendered manifest Path for one component, e.g. `component("statestore")`. */
    def component(name: String): Path =
      components.getOrElse(
        name,
        throw IllegalArgumentException(s"no shared component '$name'; have ${components.keySet}"),
      )

  /** Render the shared set for `redisHost` (default `redis:6379`) into a fresh temp dir. */
  def render(redisHost: String = ItNames.RedisHostValue): Rendered =
    val srcDir = sharedComponentsDir()
    val root = Files.createTempDirectory("dapr4s-it").nn
    val compDir = Files.createDirectories(root.resolve("components")).nn
    val components = ItNames.ComponentFileNames.map { name =>
      val rendered = Files.readString(srcDir.resolve(name)).nn.replace(ItNames.Placeholder, redisHost)
      val out = compDir.resolve(name)
      Files.writeString(out, rendered)
      name.stripSuffix(".yaml") -> out
    }.toMap
    // Shared secrets.json (the secretstore manifest points at /dapr4s-it/secrets.json).
    val secretsFile = root.resolve("secrets.json")
    Files.copy(repoRoot().resolve("scripts/it/secrets.json"), secretsFile)
    // Fresh RSA key for crypto.dapr.localstorage (the cryptostore manifest points at /dapr4s-it/keys).
    val keysDir = Files.createDirectories(root.resolve("keys")).nn
    Files.writeString(keysDir.resolve("rsa-key"), generateRsaPrivateKeyPem())
    Rendered(root, components, keysDir, secretsFile)

  private def generateRsaPrivateKeyPem(): String =
    val kpg = KeyPairGenerator.getInstance("RSA").nn
    kpg.initialize(2048)
    val der = kpg.generateKeyPair().nn.getPrivate.nn.getEncoded.nn
    val b64 = Base64.getMimeEncoder(64, Array[Byte]('\n')).nn.encodeToString(der)
    s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

  private def sharedComponentsDir(): Path =
    val d = repoRoot().resolve("scripts/it/components")
    require(Files.isDirectory(d), s"shared component dir not found: $d (cwd=${Paths.get("").toAbsolutePath})")
    d

  /** Locate the repo root (the dir holding `project.scala`) by walking up from the working dir — robust to scala-cli
    * running tests from a nested working directory.
    */
  private def repoRoot(): Path =
    var p: Path | Null = Paths.get("").toAbsolutePath
    while p != null && !Files.exists(p.resolve("project.scala")) do p = p.getParent
    require(p != null, "could not locate repo root (no project.scala found walking up from cwd)")
    p.nn
