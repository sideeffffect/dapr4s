//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import munit.FunSuite

/** Model tests for the JVM-only model types — `JobSchedule`/`JobDetails` and the `Conversation*` models. These types
  * belong to the JVM-only `JobsCapability`/`ConversationCapability` (the Dapr JS SDK has no jobs or conversation API),
  * so their cases cannot live in the cross-platform [[ModelsTest]].
  */
@scala.caps.assumeSafe
class JvmModelsTest extends FunSuite:

  // -------------------------------------------------------------------------
  // Jobs
  // -------------------------------------------------------------------------

  test("JobSchedule cases hold their data"):
    import scala.concurrent.duration.DurationInt
    assertEquals(JobSchedule.Cron("0 30 * * * *").asInstanceOf[JobSchedule.Cron].expression, "0 30 * * * *")
    assertEquals(JobSchedule.Every(5.seconds).asInstanceOf[JobSchedule.Every].period, 5.seconds)

  test("JobDetails holds all fields"):
    val now = java.time.Instant.now()
    val d = JobDetails(
      name = JobName("j"),
      data = Some(SerializedJson("\"x\"")),
      scheduleExpression = Some("@every 5s"),
      dueTime = Some(now),
      repeats = Some(3),
      ttl = None,
    )
    assertEquals(d.name, JobName("j"))
    assertEquals(d.repeats, Some(3))
    assertEquals(d.ttl, None)

  // -------------------------------------------------------------------------
  // Conversation: FinishReason / ToolChoice
  // -------------------------------------------------------------------------

  test("FinishReason.fromWire maps known reasons"):
    assertEquals(FinishReason.fromWire("stop"), FinishReason.Stop)
    assertEquals(FinishReason.fromWire("length"), FinishReason.Length)
    assertEquals(FinishReason.fromWire("tool_calls"), FinishReason.ToolCalls)
    assertEquals(FinishReason.fromWire("content_filter"), FinishReason.ContentFilter)

  test("FinishReason.fromWire is case-insensitive"):
    assertEquals(FinishReason.fromWire("STOP"), FinishReason.Stop)

  test("FinishReason.fromWire preserves unknown values"):
    assertEquals(FinishReason.fromWire("function_call"), FinishReason.Other("function_call"))

  test("ToolChoice.wireValue maps each case"):
    assertEquals(ToolChoice.Auto.wireValue, "auto")
    assertEquals(ToolChoice.None.wireValue, "none")
    assertEquals(ToolChoice.Required.wireValue, "required")
    assertEquals(ToolChoice.Named(ToolName("get_weather")).wireValue, "get_weather")

  // -------------------------------------------------------------------------
  // Conversation messages
  // -------------------------------------------------------------------------

  test("ConversationMessage smart constructors set the role"):
    assertEquals(ConversationMessage.system("s").role, ConversationMessageRole.System)
    assertEquals(ConversationMessage.user("u").role, ConversationMessageRole.User)
    assertEquals(ConversationMessage.assistant("a").role, ConversationMessageRole.Assistant)
    assertEquals(ConversationMessage.developer("d").role, ConversationMessageRole.Developer)
    assertEquals(ConversationMessage.tool("t", Some("fn")).name, Some("fn"))

  test("ConversationMessageRole enum values are distinct"):
    assertEquals(
      List(
        ConversationMessageRole.System,
        ConversationMessageRole.User,
        ConversationMessageRole.Assistant,
        ConversationMessageRole.Tool,
        ConversationMessageRole.Developer,
      ).distinct.size,
      5,
    )
