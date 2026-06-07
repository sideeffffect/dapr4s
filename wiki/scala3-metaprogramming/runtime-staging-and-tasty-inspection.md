# Runtime Staging & TASTy Inspection

> Sources: Scala 3 Reference — Run-Time Multi-Stage Programming, TASTy Inspection, Unknown
> Raw: [Staging Reference](../../raw/scala3-metaprogramming/2026-06-07-scala3-staging-reference.md); [TASTy Inspection Reference](../../raw/scala3-metaprogramming/2026-06-07-scala3-tasty-inspection-reference.md)

## Overview

The two facilities here sit at the *dynamic* and *whole-program* ends of the metaprogramming spectrum. **Runtime staging** generates and runs code at runtime using the same quotes/splices as macros but *without* `inline`. **TASTy inspection** reads the serialized typed trees of already-compiled code outside any macro. Neither is the usual mechanism for trait-derivation libraries (which are compile-time macros), but both share the quotes/reflection machinery.

## Runtime multi-stage programming

The governing rule is the **staging level = (#enclosing quotes) − (#enclosing splices)**:
- More splices than quotes → runs at **compile time** (a macro).
- Balanced → ordinary code.
- More quotes than splices → produces a typed AST to be compiled/run **at runtime**.

API (`scala.quoted.staging`, dependency `org.scala-lang %% scala3-staging`):

```scala
def run[T](expr: Quotes ?=> Expr[T])(using Compiler): T
def withQuotes[T](thunk: Quotes ?=> T)(using Compiler): T
```

`run` compiles-and-evaluates an `Expr[T]` into a real `T`; `withQuotes` just supplies a `Quotes` context. Example — generate a specialised function at runtime:

```scala
given staging.Compiler = staging.Compiler.make(getClass.getClassLoader)
val power3: Double => Double = staging.run {
  val staged: Expr[Double => Double] = '{ (x: Double) => ${ unrolledPowerCode('x, 3) } }
  println(staged.show) // "((x: Double) => x.*(x.*(x)))"
  staged
}
power3(2.0) // 8.0
```

Restrictions: top-level splices must be in inline methods; splices may only call previously compiled methods with quoted/constant/inline arguments; no nested splices without an intervening quote. Use cases: runtime-data-driven specialization/optimization (e.g. compiling a query or expression once the shape is known at runtime).

## TASTy inspection

`.tasty` files store the complete typed trees (with positions and docs) emitted alongside `.class` files. The `scala3-tasty-inspector` library lets you load and analyse them:

```scala
class MyInspector extends Inspector:
  def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
    import quotes.reflect.*
    for tasty <- tastys do val tree = tasty.ast // analyse with the same reflect API

TastyInspector.inspectTastyFiles(List("foo/Bar.tasty"))(new MyInspector)
```

Run with the compiler on the classpath (`scala -with-compiler ...`). Because it exposes the same [`quotes.reflect`](tasty-reflection.md) API as macros, TASTy inspection is the basis of external tooling (linters, doc generators, dependency analyzers, TASTyViz). It is *whole-program after compilation*, in contrast to macros which run *during* compilation of the call site.

## See Also

- [Macros: Quotes and Splices](macros-quotes-and-splices.md)
- [TASTy Reflection](tasty-reflection.md)
- [Metaprogramming Overview](metaprogramming-overview.md)
