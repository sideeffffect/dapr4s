# Primitive Obsession and Opaque Types

> Sources: Patrick Byrne, Unknown; Monix Newtypes, Unknown; nrinaudo Scala Best Practices, Unknown
> Raw: [scala-tagged-types-pbyrne.md](../../raw/scala-type-safety/scala-tagged-types-pbyrne.md); [newtypes-motivation-monix.md](../../raw/scala-type-safety/newtypes-motivation-monix.md); [nrinaudo-scala-best-practices-adts.md](../../raw/scala-type-safety/nrinaudo-scala-best-practices-adts.md)

## Overview

*Primitive obsession* is the anti-pattern of using raw primitive types (`String`, `Int`, `Boolean`) to represent semantically distinct domain concepts. Scala 3 opaque types eliminate this at zero runtime cost, and smart constructors provide boundary-level validation.

## The Problem

### Stringly-Typed Code

The most prevalent form: using `String` for every domain identifier.

```scala
// Which string is userId? Which is orderId?
def findOrder(userId: String, orderId: String): Option[Order] = ???
```

Nothing prevents transposing the arguments — the compiler cannot distinguish `userId` from `orderId` at the call site. Naming conventions, code review, and discipline are the only safeguards. This is *type safety theatre*: the code looks typed but carries none of the enforcement.

### Boolean Confusion

Booleans provide even less information than strings:

```scala
def configure(enable: Boolean, verbose: Boolean): Unit = ???
configure(true, false) // which is which?
```

### Inconsistent Validation

Without domain types, validation logic is scattered:

1. **Trusting callers** — no validation, relying on discipline
2. **Scattered defensive checks** — each method re-validates independently, inconsistently
3. **Type-encoded contracts** — custom types validate at construction; callers are trusted downstream

Only the third approach prevents errors in distributed systems. "The rules carry as the types carry down the call chain."

## The Solution: Opaque Types

Scala 3 opaque types are zero-cost wrappers transparent only inside the companion object. No boxing, no allocation — they exist only in the type checker.

```scala
opaque type UserId    = String
opaque type OrderId   = String
opaque type StoreName = String

object UserId:
  def apply(s: String): UserId =
    require(s.nonEmpty, "UserId must not be empty")
    s
  extension (id: UserId) def value: String = id

// Now it's impossible to mix them up:
def findOrder(userId: UserId, orderId: OrderId): Option[Order] = ???
```

Passing a plain `String` where `UserId` is expected is a compile error.

### Smart Constructors

Validate at construction, trust everywhere else:

```scala
opaque type EmailAddress = String
object EmailAddress:
  def apply(raw: String): Either[String, EmailAddress] =
    if raw.contains("@") then Right(raw)
    else Left(s"'$raw' is not a valid email address")
  extension (e: EmailAddress) def value: String = e
```

`Either` communicates failure explicitly. Once an `EmailAddress` exists in the system, no further validation is needed — the type carries the proof.

## Advantages Over Alternatives

### vs. Plain `String`

- Compile-time argument mismatch detection
- IDE find-usages reveals all legitimate call sites
- No defensive re-validation downstream

### vs. Case Classes (`case class UserId(value: String)`)

- Case classes box the value, opaque types do not
- Case class callers must call `.value` everywhere, breaking composability with APIs expecting `String`
- Opaque types are transparent inside the companion; extensions bridge external access

### vs. Value Classes (`class UserId(val value: String) extends AnyVal`)

- Value classes allocate on heap when used as generic type parameters, in arrays, or in type tests
- Opaque types never allocate
- No inheritance issues (value classes can't extend other classes)

## Validation Hierarchy with Type Bounds

Opaque types support subtype relationships:

```scala
opaque type NonEmptyString = String
opaque type EmailAddress <: NonEmptyString = String

object NonEmptyString:
  def apply(s: String): Either[String, NonEmptyString] =
    if s.nonEmpty then Right(s) else Left("empty string")

object EmailAddress:
  def apply(s: String): Either[String, EmailAddress] =
    if s.nonEmpty && s.contains("@") then Right(s)
    else Left("invalid email")
```

A function accepting `NonEmptyString` also accepts `EmailAddress` — the stronger guarantee is a subtype of the weaker.

## Domain Types as System Boundaries

In microservice architectures, primitive obsession propagates invalid data across service boundaries. With domain types:

- ServiceC defines `OrderId` with validation
- ServiceB uses `OrderId` in its API — the contract is visible in the type
- If ServiceA sends an invalid value, ServiceC fails with a clear message
- The failure is localized; no multi-team debugging chain

## Practical Patterns

### Boolean Replacement

```scala
sealed trait Feature derives CanEqual
case object Enabled  extends Feature
case object Disabled extends Feature

def configure(feature: Feature): Unit = ???
```

### Phantom Types for State Machines

Opaque types with phantom type parameters track state at compile time:

```scala
sealed trait Open
sealed trait Closed

opaque type Connection[State] = java.sql.Connection

object Connection:
  def open(url: String): Connection[Open]  = ???
  extension [S](c: Connection[S])
    def close(): Connection[Closed] = c.asInstanceOf

def query(c: Connection[Open]): List[Row] = ??? // closed connection = compile error
```

## See Also

- [Parse, Don't Validate](parse-dont-validate.md)
- [ADTs and Making Illegal States Unrepresentable](adts-illegal-states.md)
- [Opaque Types](../scala3-language/opaque-types.md)
