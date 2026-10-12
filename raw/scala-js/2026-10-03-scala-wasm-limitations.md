# scala-wasm documentation: Limitations

> Source: https://github.com/scala-wasm/scala-wasm.github.io/blob/e016282/src/limitations.md (scala-wasm project docs, commit e016282)
> Collected: 2026-10-12
> Published: 2026-10-03


scala-wasm targets Wasm components and WASI hosts.

## No JavaScript interop available

`ModuleKind.WasmModule` and `ModuleKind.WasmComponent` cannot depend on JavaScript. The linker analyzer rejects JS interop if it's reachable:

```text
[error] Uses JS interop with with a Wasm-only module kind:
[error]   at file:/.../HelloWorld.scala:7:34: global:console["log"](...)
[error]   at file:/.../HelloWorld.scala:7:16: global:console
[error]   called from static example.Main.main([java.lang.String)void
[error]   called from core module module initializers
[error] There were linking errors
```

Libraries built with an older Scala.js could hit this even when your code never calls `scala.scalajs.js`.[^old-scalajs]

[^old-scalajs]: For example, before Scala.js 1.20.1, it compiles varargs with `js.Array`. And before 1.19.0 lambdas were compiled as JavaScript arrow functions. For example, [cats 2.13.0](https://github.com/typelevel/cats) is built with Scala.js 1.18, so it may fail to link.

To cross compile JS and a no-JS Wasm implementation, branch with `scala.scalajs.LinkingInfo.linkTimeIf`. See [linkTimeIf](./for-library-authors.md).

## No `System.*` (`println`, `random`, `currentTimeMillis`)

`println`, `Math.random`, and `System.currentTimeMillis` are implemented with JavaScript (`console.log`, `Math.random`, `Date.now`). The analyzer rejects them the same way:

```text
[error] Uses JS interop with with a Wasm-only module kind:
[error]   at .../java/lang/System.scala:463:27: global:console["log"](line)
[error]   called from scala.Predef$.println(java.lang.Object)void
[error] Uses JS interop with with a Wasm-only module kind:
[error]   at .../java/lang/Math.scala:376:54: global:Math["random"]()
[error]   called from static java.lang.Math.random()double
[error] Uses JS interop with with a Wasm-only module kind:
[error]   at .../java/lang/System.scala:74:16: global:Date["now"]()
[error]   called from static java.lang.System.currentTimeMillis()long
```

On the Component Model, we need to reimplement those functions based on WASI.

In future, we might want to make `System` pluggable with WASIp2/WASIp3 based implementation like [#246](https://github.com/scala-wasm/scala-wasm/pull/246)

## No Scala 3

scala-wasm haven't yet supported Scala 3.[^why]

[^why]: because the scala-wasm API is still not stable.
The Scala 3 compiler for Scala.js lives in the scala3 repository, not in the Scala.js repository. So for scala-wasm to support Scala 3, support for scala-wasm would have to be implemented on the scala3 side, but features that haven't yet been upstreamed to Scala.js shouldn't be added to scala3. Of course, we could implement and provide a forked Scala 3 compiler, but the maintenance cost of a fork is high, and we don't plan to do that until Wasm Component support is upstreamed to Scala.js.

## Does XXX library work on Wasm Component?

There is no way to tell that a library supports Wasm components until linktime.
Those JS interop linking errors show up only at a link error, and currently there's no way to tell which library perfectly works on Wasm Component at earlier phases. [^another-target]

[^another-target]: I'm often asked whether we'll implement WASIp2 or WASIp3 as new compile targets like SJSIR, native, and JVM, but we have no plans to do so at the moment.
The benefit of adding a new compile target is that, users could tell whether a library supports (or claims to support) WASIp2 at library resolution.
However, splitting compile targets this way would increase the number of artifacts uploaded to Maven, and libraries that have no JS dependencies (which should work out of the box) would also need to be rebuilt for Wasm. And also, although WASI is currently the main system API, Wasm can define arbitrary system interfaces (such as WASIX and WALI), and it isn't realistic to cover all of them with compile targets. So, I'm not planning to define WASIpX as separate compile target.

