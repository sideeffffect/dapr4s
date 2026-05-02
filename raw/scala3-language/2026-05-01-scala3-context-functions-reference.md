# Scala 3 Context Functions Reference

> Source: https://docs.scala-lang.org/scala3/reference/contextual/context-functions.html
> Collected: 2026-05-01
> Published: Unknown

## Overview

Context functions are specialized functions that exclusively accept context parameters, defined using the `?=>` arrow syntax. They represent a powerful feature for writing expressive, type-safe code with minimal boilerplate.

## Core Concept

A context function type is declared like this:

```scala
type Executable[T] = ExecutionContext ?=> T
```

These functions receive synthesized arguments automatically, similar to methods with context parameters:

```scala
given ec: ExecutionContext = ...

def f(x: Int): ExecutionContext ?=> Int = ...

f(2)(using ec)   // explicit application
f(2)             // inferred application
```

## Automatic Expansion

When an expression `E` has a context function type as its expected type but isn't already a context function literal, the compiler automatically transforms it. For a type like `(T_1, ..., T_n) ?=> U`, the expression expands to:

```scala
(x_1: T1, ..., x_n: Tn) ?=> E
```

The synthesized parameters become available as givens within `E`.

## Practical Applications

### Builder Pattern Example

Context functions enable elegant DSL-style code. Here's a table construction pattern:

```scala
class Table:
  val rows = new ArrayBuffer[Row]
  def add(r: Row): Unit = rows += r

class Row:
  val cells = new ArrayBuffer[Cell]
  def add(c: Cell): Unit = cells += c

case class Cell(elem: String)
```

Constructor functions leverage context functions to eliminate plumbing:

```scala
def table(init: Table ?=> Unit) =
  given t: Table = Table()
  init
  t

def row(init: Row ?=> Unit)(using t: Table) =
  given r: Row = Row()
  init
  t.add(r)

def cell(str: String)(using r: Row) =
  r.add(new Cell(str))
```

This enables readable table construction with implicit context threading.

### Postconditions Pattern

Context functions support zero-overhead postcondition checking through opaque types and extension methods:

```scala
object PostConditions:
  opaque type WrappedResult[T] = T

  def result[T](using r: WrappedResult[T]): T = r

  extension [T](x: T)
    def ensuring(condition: WrappedResult[T] ?=> Boolean): T =
      assert(condition(using x))
      x
```

This allows assertions like `List(1, 2, 3).sum.ensuring(result == 6)` where the result is automatically in scope.

## Key Benefits

- **Type Safety**: Compile-time parameter verification
- **Reduced Boilerplate**: Automatic expansion eliminates manual threading
- **Expressiveness**: Supports sophisticated DSL patterns
- **Efficiency**: Opaque types prevent unnecessary boxing
