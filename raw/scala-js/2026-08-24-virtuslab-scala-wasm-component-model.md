# Current state of Scala and WebAssembly Component Model

> Source: https://virtuslab.com/blog/scala/current-state-of-scala (Rikito Taniguchi, VirtusLab)
> Collected: 2026-10-12
> Published: 2026-08-24

Excerpts (the page was fetched and indexed; code blocks were not captured).

## Summary

- Scala.js has a Wasm backend, but for now it assumes a JavaScript host.
- scala-wasm (https://github.com/scala-wasm/scala-wasm) is a fork where we are experimenting with Wasm modules and Wasm Component Model support without JavaScript. This makes a few things possible: running programs safely in a sandbox, such as AI-generated code or user-uploaded untrusted code; interoperability between components written in different languages, with better protection against supply chain attacks; very fast cold starts.
- scala-wasm/component-example has examples of Scala + Component Model usage.
- There are also server-side examples/templates with Spin in scala-wasm/scala-wasm-spin-templates.
- Limitations: Libraries depending on JavaScript cannot be used as-is. They need a WASI-oriented port. Experimental cats-effect + FS2 WASI work is in progress. GC+EH binaries are not supported by SpinKube or Akamai Functions yet. SpinKube support is being worked on.
- The API is subject to change without notice.

## Requirements

wasm-tools, wasmtime, scala-wasm/wit-bindgen (a scala-wasm-compatible fork), wkg, wac, cargo-component, Spin canary. The wit-bindgen version compatible with scala-wasm has not yet been upstreamed to bytecodealliance/wit-bindgen, so install it from the fork.

## sbt plugin

scala-wasm is published under the `io.github.scala-wasm` organization. In `build.sbt`, enable WebAssembly in `ESFeatures`, and set `scalaJSLinkerConfig`'s `moduleKind` to `WasmComponent`. Then the linker produces a Wasm Component. If there are WIT files under `scalaJSWitDirectory`, `wit-bindgen scala` runs at compile time and generates Scala bindings under `target/scala-*/src_managed`.

## Running an HTTP + SQLite Component with Spin

One caveat: the Wasm Components emitted by Scala.js use Wasm GC and exception handling features, so Spin canary is currently required. The TODO API template generates a component that exports WASI HTTP and imports Spin's SQLite interface.

## FAQ — Can I use library XXX?

If the library depends on JavaScript, it cannot be used as-is. Wasm Components are meant to run without a JavaScript runtime. Code paths depending on JS APIs like `scala.scalajs.js.Dynamic` and `@JSImport` fail at link time. Libraries depending on system features such as HTTP, filesystems, processes, and sockets cannot be implemented in Wasm alone; they need to be rewritten to use explicitly imported host interfaces, such as WASI HTTP or WASI filesystem. For target-specific branching use `scala.scalajs.LinkingInfo.linkTimeIf` — resolved at link time, the unused branch is dropped. scala-wasm's javalib uses this pattern in many places, including `System.currentTimeMillis()` and the regular expression engine.

## FAQ — When will this be upstreamed?

The immediate goal is to upstream Minimal Wasm (https://github.com/scala-js/scala-js/issues/5333) into Scala.js as a stepping stone toward Wasm Component support. Internally, this means upstreaming large changes that remove JavaScript dependencies from JDK APIs and the Wasm backend. After that, the next step will be to upstream Wasm Component API support into Scala.js.
