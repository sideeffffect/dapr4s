# Safe Scala: An Introduction

> Source: https://virtuslab.com/blog/scala/safe-scala-an-introduction
> Collected: 2026-05-01
> Published: 2026-04-14

**Author:** Adam Warski, Head of Scala Growth | ~13 min read

## Introduction

The article addresses AI agent security concerns. As stated: "AI agents are powerful: they can execute many day-to-day tasks thanks to their understanding of the surrounding context." However, unrestricted agent access poses risks from both incompetence and hostile scenarios like prompt injection attacks.

## Level 0: Code Generation

The foundational approach allows agents to generate and run code rather than directly accessing tools. This model theoretically proves more powerful since "the same tools can still be called from the code (not necessarily through the MCP protocol; the functionalities provided by the MCP tools can instead be given as a set of APIs)." However, without sandboxing, this remains inherently insecure.

## Level 1: Restricting Side-Effects

**Safe Scala Implementation:**
Safe Scala restricts the language and standard library to a "safe" subset. The feature is available in nightly compiler builds via `import language.experimental.safe`. According to the article, Safe Scala prevents:

- Runtime reflection
- Type casts
- Console printing
- File, network, and system process operations

**Compilation guarantees:** "Once a Safe Scala program passes compilation, it is guaranteed to execute safely."

The code example demonstrates safe compilation:
```scala
// Compiles fine
val x = List(1, 2, 3).map(n => n * 2)
```

Unsafe operations fail at compile time:
```scala
// Compiler error - file operations
new java.io.File("test.txt").delete()
```

### The @assumeSafe Annotation

Trusted libraries require explicit marking. The article explains: "Code can be marked as trusted using the `@assumeSafe` annotation. Of course, this annotation cannot be used within a Safe Scala program—otherwise, an agent could just generate `@assumeSafe`-annotated code, and circumvent our security measures."

Restricted library code compilation prevents circumvention — only code compiled in safe mode or marked with `@assumeSafe` becomes callable.

**Library Example:**
```scala
// Trusted library code
@assumeSafe
object FileOps:
  def writeToFile(path: String, content: String): Unit = ...
```

Agent-generated code can invoke this library method, but cannot write files independently.

## Level 1: Alternative Approaches

The article surveys three competing security models:

**1. Container Sandboxing:** Docker containers restrict filesystem and network access. The Sandcat project demonstrates this approach, though containers create overhead for short-lived agents.

**2. Lightweight OS-Level Sandboxing:** Tools like Bubblewrap (Linux) and SeatBelt (macOS) restrict privileges within the host OS. Anthropic's `/sandbox` in Claude Code uses similar mechanisms, filtering bash operations but offering "partial security."

**3. WASM/eBPF Approaches:**
- WebAssembly restricts I/O by default; WASI capabilities selectively enable interactions
- eBPF programs filter file and network access via kernel hooks
- The article notes: "Scala can also be compiled to WASM!"

## Level 2: Scala Capabilities

**Capture Checking Integration:**
Advanced safety arrives through capture checking, an experimental Scala 3 feature. The mechanism tracks values through which side effects occur using "thin arrows" (pure functions) versus "fat arrows" (side-effecting functions).

**The Classified Wrapper Pattern:**
A library-provided `Classified[T]` class demonstrates the concept:
```scala
class Classified[T]:
  def map[U](f: T -> U): Classified[U] = ...
```

The thin arrow `->` designates pure transformations. The article states: "Any transformations of the classified values must be pure; they cannot have any side effects."

**IO Capabilities:**
```scala
trait SharedCapability
object IO extends SharedCapability

def safePrintln(msg: String)(using IO): Unit = ...
```

The system ensures that "while pure transformations on these values can be performed, they cannot be leaked to the host LLM."

## Running Safe Scala from an Agent

Two implementation steps are necessary:

1. **MCP Server:** Expose a "compile & run safe-scala code" tool. The accompanying `tacit` proof-of-concept implements this for the TACIT paper.

2. **API Documentation:** Agent knowledge of available library interfaces requires separate tooling returning Scaladoc with "full description of the available API, Safe Scala restrictions, etc."

## Conclusion

Safe Scala provides compile-time guarantees through language restrictions and capture-based capability tracking. The critical tension involves balancing security against utility: "If agents can't access or modify the data, their utility will diminish greatly." Yet unrestricted agents have caused "dropped databases, erased emails, or prolonged system downtime." Implementation requires careful library design offering sufficient power while enforcing security constraints.
