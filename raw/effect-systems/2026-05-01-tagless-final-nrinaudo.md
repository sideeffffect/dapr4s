# A "quick" introduction to Tagless Final

> Source: https://nrinaudo.github.io/articles/tagless_final.html
> Collected: 2026-05-01
> Published: Unknown

**Author:** Nicolas Rinaudo

## Overview

This article explores tagless final encoding as a solution to the Expression Problem in Domain Specific Languages (DSLs). The author emphasizes this is foundational material; deeper exploration is available in his longer talk and Oleg Kiselyov's original lecture.

## Problem Statement

The guide uses a minimal DSL supporting integer literals and addition. Two interpreters are needed: pretty-printing and evaluation.

## Initial (Naive) Encoding

Using an algebraic data type:

```scala
enum Exp:
  case Lit(value: Int)
  case Add(lhs: Exp, rhs: Exp)
```

Interpreters follow natural recursion patterns. However, adding multiplication requires modifying `Exp`, breaking existing interpreters — this is the Expression Problem.

## Final Encoding

Rather than ADTs, functions encode expressions since functions compose. A trait parameterizes over the interpreted type `A`:

```scala
trait ExpSym[A]:
  def lit(i: Int): A
  def add(lhs: A, rhs: A): A
```

An expression becomes polymorphic:

```scala
def exp[A](sym: ExpSym[A]): A =
  import sym.*
  add(lit(1), add(lit(2), lit(4)))
```

## Syntactic Sugar

Helper functions and implicit type classes reduce verbosity:

```scala
def lit[A](i: Int)(using sym: ExpSym[A]): A = sym.lit(i)
def add[A](lhs: A, rhs: A)(using sym: ExpSym[A]): A = sym.add(lhs, rhs)
def exp[A: ExpSym]: A = add(lit(1), add(lit(2), lit(4)))
```

## Composing DSLs

Adding multiplication as a separate DSL `MultSym[A]` without modification to `ExpSym`. Combined expressions work without modification — solving the Expression Problem through composition.

## Higher-Order Languages

When expressions yield different types (integers or booleans), GADTs (Generalised Algebraic Data Types) track expression types in the tagless initial encoding:

```scala
enum Exp[A]:
  case Lit(value: Int) extends Exp[Int]
  case Add(lhs: Exp[Int], rhs: Exp[Int]) extends Exp[Int]
  case Eq(lhs: Exp[Int], rhs: Exp[Int]) extends Exp[Boolean]
```

The tagless final encoding uses higher-kinded types:

```scala
trait ExpSym[F[_]]:
  def lit(i: Int): F[Int]
  def add(lhs: F[Int], rhs: F[Int]): F[Int]
  def eq(lhs: F[Int], rhs: F[Int]): F[Boolean]
```

## Conclusion

> "final encodings are an elegant solution to a problem I don't care for much."

The Expression Problem is "an interesting intellectual exercise, but not one commonly found in concrete programming tasks." One practical exception: composing independent DSLs.

Important note: calling any method that puts type class constraints on a higher-order type "tagless final style" mistakes the implementation details for the concept.
