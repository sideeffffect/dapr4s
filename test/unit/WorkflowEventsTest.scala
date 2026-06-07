package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.duration.*

trait Events:
  def approval(timeout: FiniteDuration)(using ctx: WorkflowContext, c: JsonCodec[Resp]): Task[Resp]^{ctx}
  def signal()(using ctx: WorkflowContext, c: JsonCodec[Resp]): Task[Resp]^{ctx}
object Events extends WorkflowEvents.Derived[Events]

/** A constant, already-complete [[Task]] used by the fake context. */
@scala.caps.assumeSafe
final class ConstTask[O](value: O) extends Task[O]:
  def isDone: Boolean              = true
  def isCancelled: Boolean         = false
  def await(): O                   = value
  def map[U](f: O => U): Task[U]^{this, f} = ConstTask(f(value))

@scala.caps.assumeSafe
final class FakeWorkflowContext(resp: String) extends WorkflowContext:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: FiniteDuration): Task[T]^{this} =
    log += s"wait|${name.value}|$timeout"
    ConstTask(JsonCodec.decodeOrThrow[T](resp))
  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T]^{this} =
    log += s"wait|${name.value}"
    ConstTask(JsonCodec.decodeOrThrow[T](resp))

  def instanceId: WorkflowInstanceId = ???
  def isReplaying: Boolean           = ???
  def getInput[I: JsonCodec]: Option[I] = ???
  def callActivity[A](using d: ActivityDef[A])(input: d.Input): Task[d.Output]^{this}            = ???
  def callActivity[A](using d: ActivityDef[A], ev: d.Input =:= Unit): Task[d.Output]^{this}      = ???
  def createTimer(duration: FiniteDuration): Task[Unit]^{this}                                   = ???
  def complete[O: JsonCodec](output: O): Unit                                                    = ???
  def continueAsNew[I: JsonCodec](input: I): Unit                                                = ???
  def newUuid(): java.util.UUID                                                                  = ???

@scala.caps.assumeSafe
class WorkflowEventsTest extends FunSuite:

  test("WorkflowEvents: waitForExternalEvent with and without timeout"):
    val fake              = FakeWorkflowContext("res")
    given WorkflowContext = fake
    val client            = Events.derive
    assertEquals(client.approval(5.seconds).await(), Resp("res"))
    assertEquals(client.signal().await(), Resp("res"))
    assertEquals(fake.log.toList, List("wait|approval|5 seconds", "wait|signal"))
