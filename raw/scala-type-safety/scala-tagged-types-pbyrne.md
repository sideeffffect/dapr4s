# Scala Tagged Types and Opaque Types

> Source: https://pbyrne84.github.io/ScalaTaggedTypes.html
> Collected: 2026-05-02
> Published: Unknown

## Core Motivation: Data Stringency

The article emphasizes that "data stringency is something that is often overlooked due to the effort it takes in most languages. Scala makes this very easy." The fundamental insight is that distributed systems require robust validation at service boundaries. When ServiceA calls ServiceB which calls ServiceC, invalid data propagating through multiple teams creates coordination overhead and debugging nightmares.

### The Problem with Primitive Obsession

Systems commonly use basic types (String, Int, Boolean) without encoding domain constraints. A String parameter might require alphanumeric characters with a 300-character maximum, but this requirement lives only in documentation or developer memory—not in the type system. This creates three anti-patterns:

1. **Overly trusting callers**: No validation, relying on caller responsibility
2. **Scattered defensive checks**: Each method validates independently with inconsistent failure reporting
3. **Type-encoded contracts**: Custom types handle validation internally and propagate contracts down call chains

The article advocates for the third approach: "rules carry as the types carry down the call chain."

## What Are Tagged/Opaque Types?

Tagged types and opaque types represent the same concept across Scala versions:
- **Scala 2**: Manual implementation using `asInstanceOf` and marker traits
- **Scala 3**: Built-in opaque types feature

These types exist only at compile time. "The type does not exist at runtime. String with Tag just becomes String." This provides safety without runtime overhead.

## Technical Implementation

### The Core Trick

Tagged types leverage a casting pattern:

```scala
trait AnimalTag

"".asInstanceOf[String with AnimalTag]
```

This appends an empty trait to a type without runtime cost.

### Scala 2 Implementation

```scala
sealed trait CatTag

type StringCat = String with CatTag

object StringCat {
  def apply(noise: String): Either[String, StringCat] = {
    if (noise == "meow") {
      Right(noise.asInstanceOf[StringCat])
    } else {
      Left(s"'$noise' is not a noise cat makes")
    }
  }
}

def onlyAllowCats(cat: StringCat): Either[String, String] = {
  Right(s"cat allowed - $cat")
}
```

### Scala 3 Implementation

```scala
object Animals {
  opaque type AnimalTag = String
  opaque type StringCat <: AnimalTag with String = String
  opaque type StringDog <: AnimalTag = String

  object OpaqueStringCat {
    def apply(noise: String): Either[String, StringCat] = {
      if (noise == "meow") {
        Right(noise)
      } else {
        Left(s"'$noise' is not a noise cat makes")
      }
    }
  }
}

def onlyAllowCats(cat: StringCat): Either[String, String] = {
  Right(s"opaque cat allowed - $cat")
}

def onlyAllowAnimals(animal: AnimalTag): Either[String, String] = {
  Right(s"opaque animal - $animal")
}
```

Within the object scope, opaque types are trusted directly, reducing boilerplate. The `<:` syntax establishes subtype relationships for hierarchical validation.

## Advantages Over Value Classes

Value classes (extending `AnyVal`) can unintentionally allocate heap memory:

```scala
case class SafeString(value: String) extends AnyVal
```

Allocation occurs when:
- A value class is treated as another type
- Assigned to arrays
- Used in runtime type tests or pattern matching

Tagged types avoid these allocations entirely since they disappear after compilation.

Additionally, value class consumers must call `.value` to extract the wrapped type, breaking IDE autocompletion. Tagged types remain transparent.

## Developer Experience Benefits

### IDE Tooling Enhancement

"Using things like find usages in an IDE, we can find all potential paths that value can go down." Finding usages of a tagged type reveals legitimate contexts, whereas searching for "String" returns thousands of irrelevant results.

### Addressing Boolean Confusion

"Booleans are fairly horrible in negative outcomes." Tagged types provide context that prevents passing flags to wrong functions. Instead of ambiguous `enable(true)` calls, explicit tagged types like `TurnOff` or `TurnOn` clarify intent.

### Contract Clarity

When a function signature includes a validated type, callers know the contract is enforced: "The rules carry as the types carry down the call chain." No defensive checks needed downstream.

## Limitations

**Type Erasure**: Tagged types don't exist at runtime, preventing pattern matching:

```scala
tagged match {
  case _: StringCat => // Won't work
}
```

**Error Handling Philosophy**: The article emphasizes "fail in a clear fashion" rather than silent failures. Either types communicate validation failures explicitly:

```scala
Left(s"'$noise' is not a noise cat makes")
```

## Building Systems Backward

Design service layers first with validating types in signatures, then propagate contracts toward API entry points. This approach:

1. Clarifies rules through type interfaces
2. Enables clear error communication at boundaries
3. Reduces boundary testing overhead through single-validation trust
4. Prevents cascading invalid data through distributed systems

## Organizational Impact

In microservice architectures where multiple teams own services, data stringency prevents coordination overhead. With proper validation, failures are localized and self-evident—reducing cross-team debugging overhead.
