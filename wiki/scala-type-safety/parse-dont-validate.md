# Parse, Don't Validate

> Sources: Alexis King, 2019-11-05
> Raw: [2019-11-05-parse-dont-validate-alexis-king.md](../../raw/scala-type-safety/2019-11-05-parse-dont-validate-alexis-king.md)

## Overview

"Parse, don't validate" is a type-driven design principle that distinguishes *validation* (checking data and discarding the knowledge) from *parsing* (checking data and encoding the result in a more precise type). By preserving validation results in types, functions become total and redundant checks disappear.

## The Core Insight

Validation discards the information it learns:

```scala
def validateNonEmpty[A](list: List[A]): Unit =
  if list.isEmpty then throw Exception("empty")
  // returns Unit — knowledge is thrown away
```

Parsing preserves it:

```scala
def parseNonEmpty[A](list: List[A]): NonEmptyList[A] =
  list match
    case h :: t => NonEmptyList(h, t)
    case Nil    => throw Exception("empty")
  // returns NonEmptyList — knowledge lives in the type
```

The caller of `parseNonEmpty` never needs to re-check emptiness. The type carries the proof.

## Why Validation Is Dangerous

King introduces the concept of *shotgun parsing*: when validation checks are scattered throughout processing code rather than concentrated at entry points, it becomes impossible to know whether all paths were validated. Parsing avoids this by stratifying programs:

1. **Parsing phase** — raw input is consumed and structured data is produced; failure is possible here
2. **Execution phase** — structured data is processed; failure due to invalid input cannot occur

## Making Illegal States Unrepresentable

The design heuristic: choose the most precise data structure that eliminates the need for a check. Instead of checking at every use site, encode the invariant once at construction time.

**Before (validation approach):**
```scala
def processCommand(cmd: Command): Unit =
  require(cmd.action.nonEmpty, "action must not be empty")
  require(cmd.action == "move" || cmd.action == "rotate", "unknown action")
  // ... redundant checks everywhere
```

**After (parsing approach):**
```scala
sealed trait Command
case class Move(meters: Int)   extends Command
case class Rotate(degrees: Int) extends Command

def parseCommand(raw: RawCommand): Either[String, Command] =
  raw.action match
    case "move"   => Right(Move(raw.intParam))
    case "rotate" => Right(Rotate(raw.intParam))
    case other    => Left(s"Unknown action: $other")
```

Downstream code only ever receives a `Command` and can pattern match exhaustively without runtime surprises.

## Practical Patterns

### Smart Constructors

For cases where the type system alone can't enforce constraints (e.g., a positive integer), use opaque types with a validated factory method:

```scala
opaque type PositiveInt = Int
object PositiveInt:
  def apply(n: Int): Either[String, PositiveInt] =
    if n > 0 then Right(n)
    else Left(s"$n is not positive")
  extension (n: PositiveInt) def value: Int = n
```

The internal representation is plain `Int`, but only values passing the check enter the system as `PositiveInt`.

### System Boundaries

Parse at the boundary — the moment data enters from the outside world (HTTP requests, database rows, config files, CLI arguments). From that point forward, the rest of the program works with well-typed values and never revisits validation logic.

### Suspect `m[Unit]` Returns

King's heuristic: functions that return `IO[Unit]` or `Either[E, Unit]` whose sole purpose is raising errors usually indicate a missed opportunity to parse. The function checks a condition but returns no evidence of the check.

## Connection to Type-Driven Design

Parse, Don't Validate is the practical expression of type-driven design:
- Write functions on the data representation you *wish* to have
- Design from both ends: ideal types at the core, parsing at the boundary
- Let datatypes inform code; don't tack `Bool` fields onto structs as proxies for richer types

## See Also

- [Primitive Obsession and Opaque Types](primitive-obsession-opaque-types.md)
- [ADTs and Making Illegal States Unrepresentable](adts-illegal-states.md)
- [Opaque Types](../scala3-language/opaque-types.md)
