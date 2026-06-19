//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import io.dapr.testcontainers.{Component, DaprContainer}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Tests for [[ConversationCapability]] against Dapr's built-in `conversation.echo` component, which echoes each input
  * prompt straight back as the completion. This exercises the real `converse` wire path without needing a real LLM
  * provider.
  */
@scala.caps.assumeSafe
class ConversationCapabilityServerTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("conversation-server-test")
        .withAppPort(0)
        .withComponent(Component("echo", "conversation.echo", "v1", Collections.emptyMap())),
    )
    c.start()
    c

  test("conversation: converse returns the echoed content in a choice"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.conversation(ConversationComponentName("echo")) {
          val resp = ConversationCapability.converse(Seq(ConversationMessage.user("ping")))
          val content = resp.outputs.headOption
            .flatMap(_.choices.headOption)
            .map(_.message.content)
          assertEquals(content, Some("ping"))
        }
    }
