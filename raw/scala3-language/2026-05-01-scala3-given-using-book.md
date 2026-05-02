# Context Parameters in Scala 3 — Scala 3 Book

> Source: https://docs.scala-lang.org/scala3/book/ca-context-parameters.html
> Collected: 2026-05-01
> Published: Unknown

## Overview

Scala provides two key features for contextual abstraction:

1. **Context Parameters** — parameters that can be omitted at the call-site and automatically provided by the compiler
2. **Given Instances** (Scala 3) or **Implicit Definitions** (Scala 2) — terms the compiler uses to fill missing arguments

## The Problem

When designing systems, configuration or settings often need passing to multiple components. Without context parameters, this requires tedious explicit argument passing:

```scala
case class Config(port: Int, baseUrl: String)

def renderWebsite(path: String, config: Config): String =
  "<html>" + renderWidget(List("cart"), config) + "</html>"

def renderWidget(items: List[String], config: Config): String = ???

val config = Config(8080, "docs.scala-lang.org")
renderWebsite("/home", config)
```

## Marking Parameters as Contextual

**Scala 3 syntax** uses the `using` keyword:

```scala
def renderWebsite(path: String)(using config: Config): String =
    "<html>" + renderWidget(List("cart")) + "</html>"

def renderWidget(items: List[String])(using config: Config): String = ???
```

The compiler performs term inference — automatically locating a `Config` value in scope. You can even omit the parameter name:

```scala
def renderWebsite(path: String)(using Config): String =
    "<html>" + renderWidget(List("cart")) + "</html>"
```

**Scala 2 syntax** uses `implicit` instead of `using`.

## Explicitly Providing Arguments

When needed, explicitly supply contextual arguments:

```scala
renderWebsite("/home")(using config)
```

## Given Instances

For a single canonical value of a type, mark it as `given`:

```scala
given Config = Config(8080, "docs.scala-lang.org")
```

Then call functions without arguments:

```scala
renderWebsite("/home")
```

The compiler automatically infers the given value.
