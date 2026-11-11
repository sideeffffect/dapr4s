package dapr4s.test.wasm.unit

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wit

import dapr4s.*
import dapr4s.internal.{InvokeDispatcher, WasmStateCapability, witglue}
import dapr4s.invoke.*
import dapr4s.state.*
import dapr4swit.dapr_wasm_components.interfaces.{invocation, state as witState, state_read, types}
import dapr4swit.exports.dapr_wasm_components.interfaces.invocation_callback
import munit.FunSuite

/** Fakes of the hand-written import traits (src/wasm/glue/WitGlue.scala). Every call returns the configured result and
  * records its arguments, so the wall's mapping is tested without wasmtime, daprd or any WIT import: these tests link
  * as plain Scala.js and run on Node, and the live import objects are never reachable from them.
  */
@scala.caps.assumeSafe
final class FakeStateRead(
    getResult: wit.Result[state_read.GetStateResponse, state_read.GetError] =
      new wit.Err(state_read.GetError.KeyNotFound),
    queryResult: wit.Result[state_read.QueryResponse, state_read.QueryError] =
      new wit.Err(state_read.QueryError.NotQueryable),
) extends witglue.StateReadImports:
  var lastGet: Option[(String, String)] = None
  override def get(
      storeName: String,
      key: String,
      consistency: wit.Option[state_read.Consistency],
      metadata: state_read.Metadata,
  ): wit.Result[state_read.GetStateResponse, state_read.GetError] =
    lastGet = Some(storeName -> key)
    getResult
  override def getBulk(
      storeName: String,
      keys: Array[String],
      parallelism: wit.Option[wit.unsigned.UInt],
      metadata: state_read.Metadata,
  ): wit.Result[Array[state_read.BulkStateItem], state_read.StateError] =
    new wit.Ok(Array())
  override def query(
      storeName: String,
      query: state_read.Json,
      metadata: state_read.Metadata,
  ): wit.Result[state_read.QueryResponse, state_read.QueryError] = queryResult

@scala.caps.assumeSafe
final class FakeState(
    writeResult: wit.Result[Unit, witState.WriteError] = new wit.Ok(()),
    transactionResult: wit.Result[Unit, witState.TransactionError] = new wit.Ok(()),
) extends witglue.StateImports:
  var saved: List[state_read.StateItem] = Nil
  override def save(
      storeName: String,
      items: Array[state_read.StateItem],
      metadata: witState.Metadata,
  ): wit.Result[Unit, witState.WriteError] =
    saved = saved ++ items.toList
    writeResult
  override def delete(
      storeName: String,
      key: String,
      etag: wit.Option[String],
      options: wit.Option[state_read.StateOptions],
      metadata: witState.Metadata,
  ): wit.Result[Unit, witState.WriteError] = writeResult
  override def executeTransaction(
      storeName: String,
      operations: Array[witState.TransactionRequest],
      metadata: witState.Metadata,
  ): wit.Result[Unit, witState.TransactionError] = transactionResult

/** Unit tests of the `wasm` wall: state error mapping into dapr4s's `DaprException` family (the rules of
  * docs/WASM-COMPONENTS-PROPOSAL.md §2c) and invoke dispatch. Run with `./mill 'wasm.lib[source].wall.test'`.
  */
@scala.caps.assumeSafe
class WasmWallTest extends FunSuite:
  given JsonCodec[Int] with
    def encode(value: Int): String = value.toString
    def decode(json: String | Null): Either[JsonDecodeException, Int] =
      json match
        case null => Left(JsonDecodeException("null is not an Int"))
        case s    => s.trim.toIntOption.toRight(JsonDecodeException(s"not an Int: $s"))

  private val store = StateStoreName("statestore")
  private val key = StateStoreKey("k")

  private def state(
      reads: witglue.StateReadImports = FakeStateRead(),
      writes: witglue.StateImports = FakeState(),
  ): StateCapability.Instance =
    WasmStateCapability(reads, writes).instance(store)

  private def storeNotFound = state_read.StateError.StoreNotFound("raw daprd error body")

  test("get: key-not-found -> None"):
    val reads = FakeStateRead()
    assertEquals(state(reads = reads).get[Int](key), None)
    assertEquals(reads.lastGet, Some("statestore" -> "k"))

  test("get: found -> Some(value) and the etag"):
    val reads = FakeStateRead(getResult = new wit.Ok(state_read.GetStateResponse("42".getBytes(UTF_8), wit.Some("7"))))
    val entry = state(reads = reads).getWithETag[Int](key)
    assertEquals(entry.value, Some(42))
    assertEquals(entry.etag, Some(ETag("7")))

  test("get: store-not-found -> ComponentNotFoundException(State, <name from the call>)"):
    val reads = FakeStateRead(getResult = new wit.Err(state_read.GetError.State(storeNotFound)))
    val e = intercept[ComponentNotFoundException](state(reads = reads).get[Int](key))
    assertEquals(e.componentKind, DaprComponentKind.State)
    assertEquals(e.name, "statestore")
    assert(e.getMessage.contains("raw daprd error body"), e.getMessage)

  test("get: permission-denied -> PermissionDeniedException"):
    val denied = state_read.StateError.PermissionDenied("scoped out")
    val reads = FakeStateRead(getResult = new wit.Err(state_read.GetError.State(denied)))
    val e = intercept[PermissionDeniedException](state(reads = reads).get[Int](key))
    assertEquals(e.getMessage, "scoped out")

  test("saveWithETag: etag-mismatch -> Some(ETagMismatchException), nothing thrown"):
    val writes = FakeState(writeResult = new wit.Err(witState.WriteError.EtagMismatch(wit.None)))
    val result = state(writes = writes).saveWithETag(key, 1, ETag("3"))
    assert(result.exists(_.isInstanceOf[ETagMismatchException]), result.toString)
    assertEquals(writes.saved.map(_.key), List("k"))
    assertEquals(writes.saved.flatMap(_.etag.toScalaOption), List("3"))

  test("save: the value goes out as its JsonCodec JSON text, as UTF-8"):
    val writes = FakeState()
    state(writes = writes).save(key, 42)
    assertEquals(writes.saved.map(i => new String(i.value, UTF_8)), List("42"))

  test("save: store-not-found -> ComponentNotFoundException"):
    val writes = FakeState(writeResult = new wit.Err(witState.WriteError.State(storeNotFound)))
    intercept[ComponentNotFoundException](state(writes = writes).save(key, 1))

  test("deleteWithETag: etag-mismatch -> Some(ETagMismatchException)"):
    val writes = FakeState(writeResult = new wit.Err(witState.WriteError.EtagMismatch(wit.Some("9"))))
    assert(state(writes = writes).deleteWithETag(key, ETag("3")).isDefined)

  test("transaction: not-transactional -> DaprApiException(ERR_STATE_STORE_NOT_SUPPORTED)"):
    val writes = FakeState(transactionResult = new wit.Err(witState.TransactionError.NotTransactional))
    val e = intercept[DaprApiException](state(writes = writes).transaction(Seq(StateOp.DeleteOp(key))))
    assertEquals(e.errorCode, "ERR_STATE_STORE_NOT_SUPPORTED")

  test("queryState: not-queryable -> DaprApiException(ERR_NOT_SUPPORTED_STATE_OPERATION)"):
    val e = intercept[DaprApiException](state().queryState[Int](StateQuery("{}")))
    assertEquals(e.errorCode, "ERR_NOT_SUPPORTED_STATE_OPERATION")

  private def request(method: String, body: String): invocation_callback.InvokeRequest =
    invocation_callback.InvokeRequest(
      method,
      invocation.HttpVerb.Post,
      Array(),
      wit.None,
      "application/json",
      body.getBytes(UTF_8),
    )

  private def dispatcher: InvokeDispatcher =
    InvokeDispatcher(
      DaprApp(invokeRoutes =
        List(
          InvokeRoute[Int, Int](InvokeMethodName("double"))(_ * 2),
          InvokeRoute[Int, Int](InvokeMethodName("boom"))(_ => throw IllegalStateException("boom")),
        ),
      ),
    )

  private def body(r: invocation.HttpResponse): String = new String(r.body, UTF_8)

  test("invoke: a route answers 200 with the encoded result"):
    val r = dispatcher.onInvoke(request("double", "21"))
    assertEquals((r.status.toInt, body(r)), (200, "42"))

  test("invoke: unknown method -> 404"):
    assertEquals(dispatcher.onInvoke(request("nope", "")).status.toInt, 404)

  test("invoke: undecodable body -> 400"):
    assertEquals(dispatcher.onInvoke(request("double", "\"x\"")).status.toInt, 400)

  test("invoke: a handler exception -> 500, not a trap"):
    val r = dispatcher.onInvoke(request("boom", "1"))
    assertEquals(r.status.toInt, 500)
    assert(body(r).contains("boom"), body(r))

  // The wit-bindgen-scala binding types (compiled by 2.13) are used from Scala 3 directly, with no facade.
  test("bindings: a WIT record of another interface is usable from Scala 3"):
    val e: types.AppError = types.AppError("x")
    assertEquals(e.message, "x")
