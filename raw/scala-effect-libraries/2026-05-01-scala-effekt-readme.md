# Scala Effekt README (DISCONTINUED)

> Source: https://github.com/b-studios/scala-effekt
> Collected: 2026-05-01
> Published: Unknown

## DISCONTINUED

The Scala library "Scala Effekt" is discontinued. In 2020, its development has been superseded by the standalone programming language **Effekt** (https://effekt-lang.org/), independent of Scala.

Due to its origins, but confusingly, both carry **Effekt** in their name. To disambiguate: use **Scala Effekt** to refer to the library and **Effekt** to the standalone programming language.

The evolution of the Effekt language and its predecessors is explained at https://effekt-lang.org/evolution.

## Scala Effekt (Historical)

The **Effekt** library allows you to structure your effectful programs in a functional way. It represents an alternative to traditional monad transformer based program structuring techniques.

To use **Effekt** (tested with Scala 2.12 and Scala 2.13):

```scala
resolvers += Opts.resolver.sonatypeSnapshots
libraryDependencies += "de.b-studios" %% "effekt" % "0.4-SNAPSHOT"
```

See [Your First Effect](http://b-studios.de/scala-effekt/guides/getting-started.html) to learn how to use the library.
