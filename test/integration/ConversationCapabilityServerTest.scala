package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import io.dapr.testcontainers.{Component, DaprContainer}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Tests for [[ConversationCapability]] against Dapr's built-in `conversation.echo` component, which echoes each input
  * prompt straight back as the completion. This exercises the real alpha1 (`converse`/`converseMany`) and alpha2
  * (`chat`) wire paths without needing a real LLM provider.
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

  test("conversation: converse echoes a single prompt"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.conversation(ConversationComponentName("echo")) {
          assertEquals(ConversationCapability.converse("hello world"), "hello world")
        }
    }

  test("conversation: converseMany returns the component's outputs for several prompts"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.conversation(ConversationComponentName("echo")) {
          // The echo component collapses all inputs into a single echoed output; assert every prompt
          // is reflected back rather than depending on a particular output cardinality.
          val outputs = ConversationCapability.converseMany(Seq("alpha", "beta", "gamma"))
          assert(outputs.nonEmpty, "expected at least one output")
          val joined = outputs.mkString("\n")
          assert(joined.contains("alpha") && joined.contains("beta") && joined.contains("gamma"), s"got: $outputs")
        }
    }

  test("conversation: converseAlpha2 returns the echoed content in a choice"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.conversation(ConversationComponentName("echo")) {
          val resp = ConversationCapability.converseAlpha2(Seq(ConversationMessage.user("ping")))
          val content = resp.outputs.headOption
            .flatMap(_.choices.headOption)
            .map(_.message.content)
          assertEquals(content, Some("ping"))
        }
    }
