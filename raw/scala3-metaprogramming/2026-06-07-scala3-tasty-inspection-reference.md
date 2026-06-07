# Scala 3 — TASTy Inspection (Reference)

> Source: https://docs.scala-lang.org/scala3/reference/metaprogramming/tasty-inspect.html
> Collected: 2026-06-07
> Published: Unknown

TASTy files preserve complete typed trees of classes with source positions and documentation, useful for semantic code analysis.

## TASTyViz

Visual inspection of TASTy files (early-stage tool): github.com/shardulc/tastyviz

## Inspector

```scala
libraryDependencies += "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value
```

```scala
import scala.quoted.*
import scala.tasty.inspector.*

class MyInspector extends Inspector:
   def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
      import quotes.reflect.*
      for tasty <- tastys do
         val tree = tasty.ast
         // Do something with the tree
```

```scala
object Test:
   def main(args: Array[String]): Unit =
      val tastyFiles = List("foo/Bar.tasty")
      TastyInspector.inspectTastyFiles(tastyFiles)(new MyInspector)
```

Execute with the compiler available: `scalac -d out Test.scala` then `scala -with-compiler -classpath out Test`.

Template project: `sbt new scala/scala3-tasty-inspector.g8`
