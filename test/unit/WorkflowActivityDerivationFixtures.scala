package dapr4s.test.unit

import dapr4s.*
import dapr4s.derivation.*
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

// Fixtures for WorkflowActivityDerivationTest. Top-level definitions live here (not in the test
// class) so they don't interfere with munit's reflective instantiation. Reuses Req/Resp and their
// JsonCodec givens from DerivationFixtures.

/** A plain activity implementation class — reified by [[WorkflowActivities.derive]]. */
class CounterActivities:
  def add(input: Req)(using DaprCapability): Resp = Resp(s"added-${input.n}")
  @name("clear")
  def reset()(using DaprCapability): Resp = Resp("reset")
  // Declares an extra `using JsonCodec[Req]` the body needs (as a real activity would for a nested
  // Dapr call); the derive engine must summon it at the derive site and thread it in.
  def echo(input: Req)(using DaprCapability, JsonCodec[Req]): Resp = Resp(summon[JsonCodec[Req]].encode(input))

/** The matching typed caller facade — derived by [[WorkflowActivityCalls.derive]]. */
trait CounterCalls:
  def add(input: Req)(using ctx: WorkflowContext): Task[Resp]^{ctx}
  def reset()(using ctx: WorkflowContext): Task[Resp]^{ctx}
object CounterCalls extends WorkflowActivityCalls.Derived[CounterCalls, CounterActivities]

/** Recording fake context: logs each name-based `callActivity` and returns a fixed response. */
@scala.caps.assumeSafe
final class FakeActivityContext(resp: String) extends WorkflowContext:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty

  def callActivityByName[I: JsonCodec, O: JsonCodec](name: ActivityName, input: I): Task[O]^{this} =
    log += s"call|${name.value}|${summon[JsonCodec[I]].encode(input)}"
    ConstTask(JsonCodec.decodeOrThrow[O](resp))
  def callActivityByName[O: JsonCodec](name: ActivityName): Task[O]^{this} =
    log += s"call|${name.value}"
    ConstTask(JsonCodec.decodeOrThrow[O](resp))

  def callActivity[A](using d: ActivityDef[A])(input: d.Input): Task[d.Output]^{this}       = ???
  def callActivity[A](using d: ActivityDef[A], ev: d.Input =:= Unit): Task[d.Output]^{this} = ???
  def instanceId: WorkflowInstanceId                                                        = ???
  def isReplaying: Boolean                                                                  = ???
  def getInput[I: JsonCodec]: Option[I]                                                     = ???
  def createTimer(duration: FiniteDuration): Task[Unit]^{this}                              = ???
  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: FiniteDuration): Task[T]^{this} = ???
  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T]^{this}                   = ???
  def complete[O: JsonCodec](output: O): Unit                                               = ???
  def continueAsNew[I: JsonCodec](input: I): Unit                                           = ???
  def newUuid(): java.util.UUID                                                             = ???
