# Scala 3 — Run-Time Multi-Stage Programming (Reference)

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/staging.html
> Collected: 2026-06-07
> Published: Unknown

## Overview

Run-time multi-stage programming enables code synthesis and execution at runtime. The phase in which code runs is determined by the difference between the number of splice scopes and quote scopes in which it is embedded.

- **More splices than quotes** → executes at compile time as a macro (interpreter over typed ASTs).
- **Equal splices and quotes** → compiles and runs normally.
- **More quotes than splices** → stages at runtime, producing typed ASTs (multi-staged programming).

## Restrictions on Splices

- Top-level splices must appear within inline methods (creating macros).
- Splices must invoke previously compiled methods with quoted, constant, or inline arguments.
- Nested splices without intervening quotes are prohibited.

## API Essentials

```scala
package scala.quoted.staging

def run[T](expr: Quotes ?=> Expr[T])(using Compiler): T = ...
def withQuotes[T](thunk: Quotes ?=> T)(using Compiler): T = ...
```

`run` evaluates expressions; `withQuotes` provides context without evaluation.

## Project Setup

```bash
sbt new scala/scala3-staging.g8
```

```scala
libraryDependencies += "org.scala-lang" %% "scala3-staging" % scalaVersion.value
```

Direct compiler usage: `scalac -with-compiler -d out Test.scala` then `scala -with-compiler -classpath out Test`.

## Practical Example

```scala
import scala.quoted.*

given staging.Compiler = staging.Compiler.make(getClass.getClassLoader)

val power3: Double => Double = staging.run {
  val stagedPower3: Expr[Double => Double] =
    '{ (x: Double) => ${ unrolledPowerCode('x, 3) } }
  println(stagedPower3.show) // "((x: scala.Double) => x.*(x.*(x)))"
  stagedPower3
}

power3.apply(2.0) // 8.0
```

Runtime values guide code generation, enabling dynamic optimization based on execution-time information.
