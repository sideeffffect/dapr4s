package dapr4s.test.wasm

import dapr4s.*
import dapr4s.invoke.*
import dapr4s.state.*
import dapr4s.wasm.DaprComponent

/** The counter on dapr4s's API (`StateCapability.Instance.get/save` + `JsonCodec`), plus two error-mapping probes.
  * Nothing here knows about WIT or scala-wasm. Run by scripts/wasm/e2e.sh.
  */
object CounterComponent extends DaprComponent:
  val Store = StateStoreName("statestore")
  val Key = StateStoreKey("counter")

  given JsonCodec[Int] with
    def encode(value: Int): String = value.toString
    def decode(json: String | Null): Either[JsonDecodeException, Int] =
      json match
        case null => Left(JsonDecodeException("null is not an Int"))
        case s    => s.trim.toIntOption.toRight(JsonDecodeException(s"not an Int: $s"))

  /** Invocations without a body (`dapr invoke` with no --data). */
  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

  def counter(ignored: Unit)(using StateCapability.Instance): Int =
    val next = StateCapability.Instance.get[Int](Key).getOrElse(0) + 1
    StateCapability.Instance.save(Key, next)
    next

  given JsonCodec[String] with
    def encode(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    def decode(json: String | Null): Either[JsonDecodeException, String] =
      json match
        case null => Left(JsonDecodeException("null is not a String"))
        case s    => Right(s.stripPrefix("\"").stripSuffix("\""))

  /** Error-mapping probe: a store that daprd does not know -> `ComponentNotFoundException`. */
  def missingStore(ignored: Unit)(using state: StateCapability): String =
    try
      state.get[Int](StateStoreName("no-such-store"), Key)
      "unexpected success"
    catch case e: ComponentNotFoundException => s"${e.getClass.getSimpleName}: ${e.componentKind} ${e.name}"

  /** Error-mapping probe: a stale etag -> `Some(ETagMismatchException)`. */
  def staleEtag(ignored: Unit)(using StateCapability.Instance): String =
    StateCapability.Instance.saveWithETag(Key, 0, ETag("999999")) match
      case Some(e) => s"${e.getClass.getSimpleName}: ${e.getMessage}"
      case None    => "unexpected success"

  override def app(using state: StateCapability): DaprApp =
    given StateCapability.Instance = state.instance(Store)
    DaprApp(invokeRoutes =
      List(
        InvokeRoute[Unit, Int](InvokeMethodName("counter"))(counter),
        InvokeRoute[Unit, String](InvokeMethodName("missing-store"))(missingStore),
        InvokeRoute[Unit, String](InvokeMethodName("stale-etag"))(staleEtag),
      ),
    )
