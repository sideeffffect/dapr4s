# Newtypes: Motivation

> Source: https://newtypes.monix.io/docs/motivation.html
> Collected: 2026-05-02
> Published: Unknown

## The Core Problem: Type Safety

Newtypes address a fundamental issue in statically-typed programming: relying on primitive types like `String` for semantically distinct concepts creates opportunities for error. Rather than passing multiple string parameters:

```scala
def register(
  firstName: String,
  lastName: String,
  emailAddress: String,
): IO[Account] = ???
```

A newtype approach provides compile-time protection:

```scala
def register(
  fname: FirstName,
  lname: LastName,
  ea: EmailAddress,
): IO[Account] = ???
```

This prevents accidental parameter reordering or mismatched values. Without type safety, developers must rely on named parameters and discipline alone—the compiler cannot catch mistakes.

## Alternative Type Class Instances

Newtypes also enable alternative type class implementations. For instance, Scala's `Ordering` provides ascending order by default, but a newtype wrapper allows descending order without redefining the original instance:

```scala
case class ReversedInt(value: Int)

object ReversedInt {
  implicit val ord: Ordering[ReversedInt] =
    (x, y) => -1 * implicitly[Ordering[Int]].compare(x.value, y.value)
}
```

This pattern is superior to modifying existing instances, especially for types not under your control.

## Why This Library Exists

While case classes work, they incur runtime overhead through boxing and unboxing. Scala 3's opaque types solve this, but the library provides stable, macro-free helpers compatible with both Scala 2 and 3.
