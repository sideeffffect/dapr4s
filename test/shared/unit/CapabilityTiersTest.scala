package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.secrets.*, dapr4s.workflow.*
import munit.FunSuite

/** Universal fake secrets capability: `apply` binds a [[FakeSecretsInstance]] to the requested store. */
@scala.caps.assumeSafe
final class FakeSecretsUniversal extends SecretsCapability:
  def instance(storeName: SecretStoreName): SecretsCapability.Instance^{this} =
    // WHAT: asInstanceOf erases the fresh capture set of the new instance.
    // WHY: CC infers `^{fresh}` for a newly allocated ExclusiveCapability, but the override must return `^{this}`.
    // WHY SAFE: the instance holds only the store name — no capability of its own — so tying it to `this` is exact.
    // WHERE: the same pattern as the production `*CapabilityUniversalImpl` classes in `dapr4s.internal`.
    new FakeSecretsInstance(storeName).asInstanceOf[SecretsCapability.Instance]
  def get(storeName: SecretStoreName, key: SecretKey, metadata: Map[MetadataKey, MetadataValue]): Option[SecretValue] =
    instance(storeName).get(key, metadata)
  def getBulk(storeName: SecretStoreName, metadata: Map[MetadataKey, MetadataValue]): Map[SecretKey, SecretValue] =
    instance(storeName).getBulk(metadata)

/** Store-bound fake secrets capability: answers every key with `"<store>/<key>"`. */
@scala.caps.assumeSafe
final class FakeSecretsInstance(val storeName: SecretStoreName) extends SecretsCapability.Instance:
  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue]): Option[SecretValue] =
    Some(SecretValue(s"${storeName.value}/${key.value}"))
  def getBulk(metadata: Map[MetadataKey, MetadataValue]): Map[SecretKey, SecretValue] =
    Map(SecretKey("all") -> SecretValue(storeName.value))

/** The capability tiers: universal → `Instance` narrowing by name, and full → `ReadOnly` narrowing by authority.
  *
  * The runtime tests check that `apply(name)` binds the right name. The `compileErrors` tests check the static
  * guarantees: a `ReadOnly` tier has no write members, and narrowing a `ReadOnly` universal capability by name keeps it
  * read-only. Each negative check has a positive twin, so a check cannot pass just because the snippet is malformed.
  */
@scala.caps.assumeSafe
class CapabilityTiersTest extends FunSuite:

  private val store = SecretStoreName("vault")
  private val stateStore = StateStoreName("s")
  private val stateKey = StateStoreKey("k")

  test("universal apply(name) returns an Instance bound to that name"):
    val universal = FakeSecretsUniversal()
    val instance = universal.instance(store)
    assertEquals(instance.storeName, store)
    assertEquals(instance.get(SecretKey("k")), Some(SecretValue("vault/k")))
    assertEquals(universal.get(store, SecretKey("k")), Some(SecretValue("vault/k")))
    assertEquals(universal.getBulk(store), Map(SecretKey("all") -> SecretValue("vault")))

  test("ReadOnly universal apply(name) returns an Instance.ReadOnly bound to that name"):
    val readOnly: SecretsCapability.ReadOnly = FakeSecretsUniversal()
    val instance: SecretsCapability.Instance.ReadOnly^{readOnly} = readOnly.instance(store)
    assertEquals(instance.storeName, store)
    assertEquals(instance.get(SecretKey("k")), Some(SecretValue("vault/k")))

  // Each snippet is a lambda over the capability under test, so the checks run (as asserts on the compile-time
  // error text) without needing a fake for every capability family.

  test("ReadOnly universal apply(name) statically yields Instance.ReadOnly, not Instance"):
    assertNoDiff(
      compileErrors("(c: SecretsCapability) => { val i: SecretsCapability.Instance^{c} = c.instance(store) }"),
      "",
    )
    assertNoDiff(
      compileErrors(
        "(c: SecretsCapability.ReadOnly) => { val i: SecretsCapability.Instance.ReadOnly^{c} = c.instance(store) }",
      ),
      "",
    )
    assert(
      compileErrors(
        "(c: SecretsCapability.ReadOnly) => { val i: SecretsCapability.Instance^{c} = c.instance(store) }",
      ).nonEmpty,
    )

  private def assertNotMember(errors: String): Unit =
    assert(errors.contains("is not a member"), s"expected a 'not a member' error, got: $errors")

  test("StateCapability.ReadOnly has no write methods"):
    assertNoDiff(compileErrors("(c: StateCapability) => c.delete(stateStore, stateKey)"), "")
    assertNotMember(compileErrors("(c: StateCapability.ReadOnly) => c.delete(stateStore, stateKey)"))
    assertNotMember(compileErrors("(c: StateCapability.ReadOnly) => c.save(stateStore, stateKey, 1)"))
    assertNotMember(compileErrors("(c: StateCapability.ReadOnly) => c.transaction(stateStore, Nil)"))

  test("StateCapability.Instance.ReadOnly has no write methods"):
    assertNoDiff(compileErrors("(c: StateCapability.Instance) => c.delete(stateKey)"), "")
    assertNotMember(compileErrors("(c: StateCapability.Instance.ReadOnly) => c.delete(stateKey)"))
    assertNotMember(compileErrors("(c: StateCapability.Instance.ReadOnly) => c.save(stateKey, 1)"))
    assertNotMember(compileErrors("(c: StateCapability.Instance.ReadOnly) => c.transaction(Nil)"))

  test("SecretsCapability.Instance.ReadOnly has no getBulk"):
    assertNoDiff(compileErrors("(c: SecretsCapability.Instance) => c.getBulk()"), "")
    assertNoDiff(compileErrors("(c: SecretsCapability.Instance.ReadOnly) => c.get(SecretKey(\"k\"))"), "")
    assertNotMember(compileErrors("(c: SecretsCapability.Instance.ReadOnly) => c.getBulk()"))

  test("WorkflowCapability.Instance.ReadOnly cannot terminate"):
    assertNoDiff(compileErrors("(c: WorkflowCapability.Instance) => c.terminate()"), "")
    assertNoDiff(compileErrors("(c: WorkflowCapability.Instance.ReadOnly) => c.getStatus()"), "")
    assertNotMember(compileErrors("(c: WorkflowCapability.Instance.ReadOnly) => c.terminate()"))
    assertNotMember(compileErrors("(c: WorkflowCapability.Instance.ReadOnly) => c.suspend()"))

  test("WorkflowCapability.ReadOnly cannot start or narrow to a controlling instance"):
    assertNoDiff(compileErrors("(c: WorkflowCapability) => c.instance(WorkflowInstanceId(\"id\")).terminate()"), "")
    assertNotMember(compileErrors("(c: WorkflowCapability.ReadOnly) => c.start(WorkflowName(\"w\"))"))
    assertNotMember(compileErrors("(c: WorkflowCapability.ReadOnly) => c.instance(WorkflowInstanceId(\"id\")).terminate()"))

  test("companion instance(name) returns the narrowed capability from the one in scope"):
    given universal: SecretsCapability = FakeSecretsUniversal()
    val instance = SecretsCapability.instance(store)
    assertEquals(instance.storeName, store)
    assertEquals(instance.getBulk(), Map(SecretKey("all") -> SecretValue("vault")))
    val readOnly = SecretsCapability.ReadOnly.instance(store)
    assertEquals(readOnly.get(SecretKey("k")), Some(SecretValue("vault/k")))

  test("ReadOnly.instance(name) statically returns Instance.ReadOnly"):
    assertNoDiff(
      compileErrors("(c: SecretsCapability) => SecretsCapability.instance(store)(using c).getBulk()"),
      "",
    )
    assertNotMember(
      compileErrors("(c: SecretsCapability.ReadOnly) => SecretsCapability.ReadOnly.instance(store)(using c).getBulk()"),
    )
