# Scala 3: Opaque Types Quickly Explained

> Source: https://rockthejvm.com/articles/scala-3-opaque-types
> Collected: 2026-05-01
> Published: Unknown

## Overview

This article from Rock the JVM explains opaque types, a Scala 3 feature that enables zero-overhead type definitions by wrapping existing types with custom functionality.

## Background and Motivation

Creating wrapper types incurs overhead. The example uses a social network context where a `Name` type wraps a `String` to enforce validation rules like capitalizing the first letter. "This new Name type incurs some sort of overhead."

## Enter Opaque Types

Opaque types allow defining a type as equivalent to another within a specific scope while hiding this implementation from external code:

```scala
object SocialNetwork {
    opaque type Name = String
}
```

This approach provides zero overhead while maintaining type safety boundaries.

## Defining an Opaque Type's API

A companion object provides static methods and extension methods supply instance functionality:

```scala
object Name {
    def fromString(s: String): Option[Name] =
      if (s.isEmpty || s.charAt(0).isLower) None else Some(s)
}

extension (n: Name) {
    def length: Int = n.length
}
```

Outside the definition scope, the underlying type cannot be accessed directly.

## Opaque Types with Bounds

Type bounds enable hierarchical relationships between opaque types, supporting inheritance-like substitution patterns in graphics applications.

## Conclusion

Opaque types offer flexibility for expressing new types using existing ones without performance penalties, despite limiting direct access to underlying type APIs.
