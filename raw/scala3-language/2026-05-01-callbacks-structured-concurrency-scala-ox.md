# Integrating Callbacks with Structured Concurrency in Scala (Ox)

> Source: https://softwaremill.com/integrating-callbacks-with-structured-concurrency-in-scala/
> Collected: 2026-05-01
> Published: 2024-04-29

**Author:** Krzysztof Ciesielski | ~9 min read
**Tags:** Scala, Functional Programming, Tapir, Structured Concurrency

## Introduction

Structured concurrency, introduced as a preview feature in Java 19, enables developers to manage asynchronous task lifecycles within clearly defined supervision boundaries. As described in the official documentation, tasks can only execute within explicit scopes, ensuring predictable resource cleanup.

The Scala ecosystem addresses this through the Ox library, which provides safe, powerful concurrency operations built on structured concurrency principles while leveraging Scala's syntax and type system advantages.

## Core Concepts

### Scopes and Forks

A fundamental rule of structured concurrency states: "Once the code block passed to the scope completes, any daemon forks that are still running are interrupted." This ensures predictable lifecycle boundaries that developers can easily follow.

Basic usage in Scala with Ox:

```scala
import ox.*
import scala.concurrent.duration.*

supervised {
  val f1 = fork {
    sleep(2.seconds)
    1
  }

  val f2 = fork {
    sleep(1.second)
    2
  }

  (f1.join(), f2.join())
}
```

### Ox Library Features

The library supports multiple concurrency patterns:

**Fork computation:**
```scala
def forkComputation(p: Int)(using Ox): Fork[Int] = fork {
  sleep(p.seconds)
  p + 1
}

supervised {
  val f1 = forkComputation(2)
  val f2 = forkComputation(4)
  (f1.join(), f2.join())
}
```

**Retry policies:**
```scala
retryEither(
  RetryPolicy.backoff(3, 100.millis, 5.minutes, Jitter.Equal),
  ResultPolicy.retryWhen(_ != "fatal error")
)(eitherOperation)
```

**Timeout management:**
```scala
val result1: Try[Int] = Try(timeout(1.second)(computation))
// failure: TimeoutException
```

**Stream processing:**
```scala
supervised {
  Source
    .fromValues(1, 2, 3)
    .map(_ + 1)
    .foreach { … }
}
```

### Channels and Sources

Beneath Ox's `Source` abstraction exists a `Channel[A]` construct similar to a queue but with enhanced features including completion support and downstream error propagation:

```scala
val c = Channel.bufferedDefault[String]
channel.send("msg")
channel.receiveOrClosed() // String | Closed
```

Operations like `send` and `receive` occur directly at the call site without requiring forks. However, transformations like `map` and `filter` run asynchronously and require a supervision context.

## Problem Definition

Standard concurrency patterns work well for request-response scenarios where a parent flow produces a defined result. However, callback-based interfaces present challenges—handlers execute detached from any controllable main flow.

**Example problematic structure:**
```scala
class MessageHandler(transformationPipeline: Source[Message] => Source[Message]):
  val channel: Channel[Message] = Channel.buffered[Message]

  def onMessage(msg: Message): Unit =
     channel.send(msg)

  def startProcessing(): Unit = 
    fork { // Won't compile—no scope exists!
      transformationPipeline(channel: Source[Message]) 
      .foreach { case req: Request => handleRequest(msg) }
    }
```

The background task cannot execute because it requires an enclosing scope. Additionally, the endpoint of processing is unknown—messages may continue arriving until the channel closes via another callback.

## Solution: The OxDispatcher

The solution involves creating special long-living scopes. An outer scope manages ad-hoc forks while remaining active throughout handler lifecycles. Nested scopes around individual processing tasks ensure interruption affects only that specific pipeline.

This leverages Ox's `Actor`, which operates similarly to Akka's actor model. Messages enter an inbox via `tell()`, returning immediately, while a background fork processes them:

```scala
class OxDispatcher()(using ox: Ox):
  private class Runner:
    def runAsync(thunk: Ox ?=> Unit, onError: Throwable => Unit): Unit =
    fork {
        try supervised(thunk)
        catch case e => onError(e)
    }.discard

  private val actor = Actor.create(new Runner)

  def runAsync(thunk: Ox ?=> Unit)(onError: Throwable => Unit): Unit = 
    actor.tell(_.runAsync(thunk, onError))
```

The dispatcher hides fork and scope complexity. Note the context function signature `Ox ?=> Unit`, a Scala 3 feature enabling implicit parameter passing while leveraging an implicit scope within the actor.

**Improved MessageHandler:**
```scala
class MessageHandler(
  transformationPipeline: Ox ?=> Source[Message] => Source[Message], 
  oxDispatcher: OxDispatcher
):
  val channel: Channel[Message] = Channel.buffered[Message]

  def onMessage(msg: Message): Unit =
     channel.send(msg)

  def startProcessing(): Unit = 
    dispatcher.runAsync( 
      transformationPipeline(channel: Source[Message]) 
      .foreach { case req: Request => handleMessage(msg) },
      onError = e => logger.error(e)
    )

  def handleMessage(msg: Message): Unit = 
    // actual processing
```

### Architecture Overview

The dispatcher establishes a safety supervision zone. The broader question becomes "in what scope should I create the dispatcher?" Since handlers persist throughout server lifecycle, a global `supervised {}` wrapper typically manages a single dispatcher created at startup.

## Implementation in Tapir

The tapir-netty-sync server abstracts these details from users:

```scala
NettySyncServer()
  .host("0.0.0.0")
  .port(8080)
  .startAndWait()
```

This internally creates a managed long-living scope and `OxDispatcher`. Currently, Tapir's NettySyncServer supports Ox for WebSocket endpoints accepting an `Ox ?=> Source[Req] => Source[Resp]` processing pipeline:

```scala
object WebSocketNettySyncServer:
  val wsEndpoint =
    endpoint.get
    .in("ws")
    .out(webSocketBody[String, CodecFormat.TextPlain, String, 
         CodecFormat.TextPlain](OxStreams))

  val wsPipe: Pipe[String, String] =
    requestStream => requestStream.map(_.toUpperCase)

  val wsServerEndpoint = wsEndpoint.serverLogicSuccess[Id](_ => wsPipe)

  def main(args: Array[String]): Unit =
    NettySyncServer()
    .host("0.0.0.0")
    .port(8080)
    .addEndpoint(wsServerEndpoint)
    .startAndWait()
```

Underneath, a reactive Publisher/Subscriber system integrates with Netty's callback machinery via `OxDispatcher` while maintaining functional, declarative programming patterns.

## Limitations

While this approach reconciles callback and structured concurrency paradigms, users should understand constraints:

**Single actor bottleneck:** The dispatcher actor manages FIFO task scheduling. With numerous concurrent entities calling `runAsync()`, fairness isn't sophisticated—faster schedulers receive priority. Testing with 2500 concurrent users showed no issues, though some scenarios might require multiple separate actors.

**Fire-and-forget semantics:** Tasks started via `runAsync()` may run indefinitely. Developers must carefully ensure thunks have well-defined termination conditions. The call site differs from actual fork initiation, preventing implementation of `runAsyncCancellable()` variants.

**Unbounded mailbox:** The dispatcher actor's inbox lacks capacity controls. External, higher-level structures must manage task boundaries when needed. Within Tapir's `NettySyncServer`, server internals handle these safety concerns transparently.

## Conclusion

Structured concurrency initially appears incompatible with callback-based interfaces, yet this approach demonstrates both paradigms coexist safely without sacrificing safety or developer experience. The Ox library combined with dispatcher patterns enables callback integration while preserving concurrency guarantees.

For direct-style Scala development using Ox on Java 21 virtual threads, exploring Tapir's server options provides practical implementation examples.
