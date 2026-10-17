package dapr4s.test.wasm.probe

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wit

import dapr4s.internal.witglue
import dapr4swit.dapr_wasm_components.interfaces.{actors, conversation, invocation, jobs_read}
import dapr4swit.exports.dapr_wasm_components.interfaces.invocation_callback

/** Calls each WIT import whose flattened params exceed MAX_FLAT_PARAMS = 16 (params passed through linear memory),
  * which the scala-wasm linker lowers since https://github.com/scala-wasm/scala-wasm/pull/268 (the 1.22.1-wasm.6
  * release could not). Linking this component at all proves the import side is emitted; scripts/wasm/e2e.sh also checks
  * that the component imports exactly these four, and runs them against a real daprd (dapr-wasm-components 0.7.5
  * providers): `converse` goes to daprd's `conversation.echo` component, so the answer must be the very text the probe
  * sent, which proves the params (passed through linear memory) reach the host intact; `register-timer` on a type this
  * app does not host must be daprd's typed refusal naming the actor the probe sent; `schedule` / `register-reminder`
  * must come back as well-formed WIT results. Each route answers `ok: ...` or `error: <the WIT error>`.
  */
object ProbeHandler extends witglue.InvocationCallbackExports:
  private def respond(status: Int, text: String): invocation.HttpResponse =
    invocation.HttpResponse(status.toShort, Array(wit.Tuple2("content-type", "text/plain")), text.getBytes(UTF_8))

  private def converse(): String =
    val input = conversation.ConversationInput(
      Array(
        conversation.Message.User(
          conversation.ParticipantMessage(wit.Some("probe"), Array(conversation.ContentPart("hello from scala-wasm"))),
        ),
      ),
      wit.None,
    )
    witglue.ConversationImports
      .converse("echo", Array(input), wit.None)
      .fold(
        ok => s"ok: ${ok.outputs.flatMap(_.choices).map(_.message.content).mkString("|")}",
        err => s"error: $err",
      )

  private def schedule(): String =
    val job = jobs_read.Job(wit.Some("@every 1h"), wit.None, wit.None, wit.None, wit.None, wit.None)
    witglue.JobsImports.schedule("probe-job", job, true).fold(_ => "ok: scheduled probe-job", err => s"error: $err")

  private def registerReminder(): String =
    val reminder = actors.Reminder(wit.Some("1h"), wit.None, wit.None, wit.None)
    witglue.ActorsImports
      .registerReminder("ProbeActor", "probe-1", "probe-reminder", reminder)
      .fold(_ => "ok: reminder registered", err => s"error: $err")

  private def registerTimer(): String =
    val timer = actors.Timer(wit.Some("1h"), wit.None, wit.None, wit.None, wit.None)
    witglue.ActorsImports
      .registerTimer("ProbeActor", "probe-1", "probe-timer", timer)
      .fold(_ => "ok: timer registered", err => s"error: $err")

  override def onInvoke(request: invocation_callback.InvokeRequest): invocation.HttpResponse =
    request.method match
      case "converse"          => respond(200, converse())
      case "schedule"          => respond(200, schedule())
      case "register-reminder" => respond(200, registerReminder())
      case "register-timer"    => respond(200, registerTimer())
      case other               => respond(404, s"no method $other")
