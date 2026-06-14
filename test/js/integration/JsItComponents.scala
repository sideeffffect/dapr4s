//> using target.platform "scala-js"
package dapr4s.test.integration

import scala.scalajs.js
import dapr4styped.daprTestcontainerNode.distDaprContainerMod.DaprContainer
import dapr4styped.testcontainers.buildGenericContainerGenericContainerMod.GenericContainer
import dapr4styped.testcontainers.buildTypesMod.ContentToCopy

/** Applies the CANONICAL shared Dapr component set (scripts/it/components/<name>.yaml) to a [[DaprContainer]] for the
  * JS testcontainers topology — the Scala.js twin of [[JvmItComponents]].
  *
  * The YAML files are the single source of truth; only `${DAPR4S_IT_REDIS_HOST}` is environment-specific. Here it is
  * substituted with `redis:6379` (the redis container's network alias on the shared Docker network) — mirroring the JVM
  * fixture, which uses the same value, and scripts/it/render-components.sh, which the (now-retired) host-network shell
  * harness used with `localhost:6391`.
  *
  * Components are rendered to a temp dir and fed to `DaprContainer.withComponentFromPath` (→ `/dapr-resources` inside
  * the container). The secretstore's `secrets.json` and a freshly minted RSA key for the cryptostore are copied to
  * `/dapr4s-it` (the in-container paths the secretstore/cryptostore manifests reference) via
  * `withCopyContentToContainer` — the JS twin of the JVM fixture's
  * `withCopyFileToContainer(MountableFile.forHostPath(...))`.
  */
@scala.caps.assumeSafe
object JsItComponents:

  /** Network alias of the redis container; the rendered redisHost is `redis:6379`. */
  val RedisAlias = "redis"
  val RedisHostValue = s"$RedisAlias:6379"

  private val Placeholder = "${DAPR4S_IT_REDIS_HOST}"

  /** Canonical component manifest file names (= scripts/it/components/<name>.yaml). */
  val ComponentFileNames: List[String] =
    List("statestore", "pubsub", "lockstore", "configstore", "cryptostore", "secretstore").map(_ + ".yaml")

  /** Configuration items both harnesses seed into redis (`value||version`). */
  val SeededConfig: List[(String, String)] =
    List("dapr4s-it-cfg-a" -> "alpha||v1", "dapr4s-it-cfg-b" -> "beta||v2")

  /** The `redis-cli MSET ...` argv that seeds [[SeededConfig]] (run via the redis container's exec). */
  val SeedConfigArgv: js.Array[String] =
    js.Array("redis-cli", "MSET") ++ js.Array(SeededConfig.flatMap((k, v) => List(k, v))*)

  /** Render the canonical set for `redisHost` and apply it (plus secrets.json + a fresh RSA key) to `dc`, returning the
    * configured container. Mirrors [[JvmItComponents.render]] + `SharedDaprItSuite`'s `withComponent` /
    * `withCopyFileToContainer` loop.
    */
  def configure(dc: DaprContainer, redisHost: String = RedisHostValue): DaprContainer =
    val root = repoRoot()
    val compSrc = NodePath.join(root, "scripts", "it", "components")
    val tmp = NodeFs.mkdtempSync(NodePath.join(NodeOs.tmpdir(), "dapr4s-it-"))
    var c = dc
    for name <- ComponentFileNames do
      val rendered = NodeFs.readFileSync(NodePath.join(compSrc, name), "utf8").replace(Placeholder, redisHost)
      val out = NodePath.join(tmp, name)
      NodeFs.writeFileSync(out, rendered)
      c = c.withComponentFromPath(out)
    // Shared secrets.json (the secretstore manifest points at /dapr4s-it/secrets.json) and a fresh
    // RSA key for crypto.dapr.localstorage (the cryptostore manifest points at /dapr4s-it/keys).
    // 0x1a4 = 0644: daprd runs as a non-root user and otherwise fails the components with
    // "permission denied"; Docker creates the world-traversable parent dirs from the tar.
    val secrets = NodeFs.readFileSync(NodePath.join(root, "scripts", "it", "secrets.json"), "utf8")
    // `withCopyContentToContainer` is inherited from the testcontainers GenericContainer; the
    // ScalablyTyped DaprContainer facade is generated as `extends js.Object` (the parent class is
    // not surfaced), so reach the method through the GenericContainer view of the same JS object.
    c.asInstanceOf[GenericContainer]
      .withCopyContentToContainer(
        js.Array(
          contentToCopy(secrets, "/dapr4s-it/secrets.json", 0x1a4),
          contentToCopy(generateRsaPrivateKeyPem(), "/dapr4s-it/keys/rsa-key", 0x1a4),
        ),
      )
      .asInstanceOf[DaprContainer]

  private def contentToCopy(content: String, target: String, mode: Int): ContentToCopy =
    js.Dynamic.literal(content = content, target = target, mode = mode).asInstanceOf[ContentToCopy]

  /** A fresh PKCS#8 RSA private key in PEM ("BEGIN PRIVATE KEY"), matching the key the JVM fixture mints via
    * `java.security.KeyPairGenerator`.
    */
  private def generateRsaPrivateKeyPem(): String =
    val res = NodeCrypto.generateKeyPairSync(
      "rsa",
      js.Dynamic
        .literal(
          modulusLength = 2048,
          publicKeyEncoding = js.Dynamic.literal(`type` = "spki", format = "pem"),
          privateKeyEncoding = js.Dynamic.literal(`type` = "pkcs8", format = "pem"),
        )
        .asInstanceOf[js.Object],
    )
    res.privateKey.asInstanceOf[String]

  /** Locate the repo root (the dir holding `project.scala`): the `DAPR4S_REPO_ROOT` env the test runner exports, else
    * walk up from `process.cwd()` — robust to the linked test module running from a temp dir. The JS twin of
    * [[JvmItComponents]]'s `repoRoot`.
    */
  def repoRoot(): String =
    NodeProcess.env.get("DAPR4S_REPO_ROOT").flatMap(_.toOption).filter(_.nonEmpty).getOrElse {
      var dir = NodeProcess.cwd()
      var found: String | Null = null
      var searching = true
      while searching do
        if NodeFs.existsSync(NodePath.join(dir, "project.scala")) then
          found = dir
          searching = false
        else
          val parent = NodePath.dirname(dir)
          if parent == dir then searching = false else dir = parent
      val f = found
      if f == null then
        throw RuntimeException(s"could not locate repo root (no project.scala) from cwd=${NodeProcess.cwd()}")
      f
    }
