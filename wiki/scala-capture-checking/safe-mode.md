# Safe Mode

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [safe](../../raw/scala-capture-checking/2026-05-01-safe.md)
> Updated: 2026-05-01

## Overview

Safe mode is a restricted subset of Scala 3 built on capture checking. It is designed for contexts where untrusted code (for example, AI agent-generated code) must be validated and executed without being able to subvert the capability tracking guarantees. Safe mode prevents capabilities from being "laundered" through unsafe language features.

## Six Restrictions

Safe mode enforces:

| Restriction | What it blocks |
|---|---|
| No unsafe type operations | Unchecked type casts (`asInstanceOf`), unchecked pattern matches |
| No unsafe modules | Access to `caps.unsafe` escape hatches |
| No unchecked annotations | `@unchecked` and similar markers that suppress checking |
| No reflection | Runtime reflection (`scala.reflect`, `java.lang.reflect`) |
| Effect tracking required | Must compile with `captureChecking` enabled |
| Safe library access only | Can only call global objects that are themselves safe-compiled |

## Annotation Escape Hatches for Library Authors

**`@assumeSafe`** — Marks a component as safe even if it was compiled outside safe mode. Useful for library code that uses untracked mutations (e.g., internal caches) in ways that do not affect external observable behavior.

**`@rejectSafe`** — Prevents specific members from being accessible in safe mode, even if the containing class is otherwise safe.

## Exception Handling in Safe Mode

Safe mode explicitly permits exceptions. The design decision: rather than requiring all possible exception types to be tracked as capabilities throughout the untrusted code, safe mode expects callers to wrap untrusted code in `Try` blocks. This contains any thrown exceptions as values before they escape, keeping the safety model tractable.

## Use Case: Agent Code Sandboxing

The TACIT system (and similar agent harness projects) use safe mode to:
1. Accept code submitted by an AI agent
2. Compile it under safe mode to verify capability discipline
3. Confirm that capabilities flow only through typed APIs
4. Execute the code knowing it cannot leak capabilities or bypass the type system

This creates a compile-time sandbox — the type checker becomes the security enforcement boundary.

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capabilities and Resources](capabilities-and-resources.md)
- [Safe Exceptions](safe-exceptions.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
