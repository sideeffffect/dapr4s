# Exposing a synchronous-looking blocking API on Scala.js (js.async/js.await, JSPI, Wasm backend) — research for dapr4s

> Source: scala-js.org release notes (1.17.0, 1.19.0, 1.20.1, 1.21.0) and WebAssembly backend docs; scala-js/scala-js JSPI.scala; chromestatus.com; nodejs/node#60014 + Node 25.0.0 release post; un-ts/synckit; typelevel/cats-effect#529; VirtusLab/scala-cli v1.5.2 release
> Collected: 2026-06-11
> Published: Unknown

Context: dapr4s today is a direct-style sync library (`def get(key): Option[T]`) that parks **virtual threads** via `CompletableFuture.get()` (`/home/ondra/.t3/worktrees/dapr4s/t3code-c916dd05/src/Dapr.scala`, `src/internal/MonoOps.scala`), plus an **inbound** app server for pubsub/actor/binding callbacks (`src/internal/DaprAppServer.scala`). The question is what can replicate "looks blocking, doesn't block" on Scala.js, where the Dapr JS SDK returns `js.Promise`.

---

## 1. `scala.scalajs.js.async` / `js.await`

- **Introduced in Scala.js 1.19.0** (announced 2025-04-21). `js.async { ... }` returns a `js.Promise[A]`; semantics are exactly an immediately-invoked JS async function: `(async () => body)()`. `js.await(p: js.Promise[A]): A` resumes when the promise resolves (or throws on rejection). Source: [Announcing Scala.js 1.19.0](https://www.scala-js.org/news/2025/04/21/announcing-scalajs-1.19.0/).
- **Where `js.await` is allowed (JS backend):** only **lexically/directly inside the `js.async` block** — it may appear in conditional branches, `while` loops and `try/catch/finally`, but **not** nested in any local method, local class, by-name argument, or closure/lambda (which rules out `for`-comprehensions and `.map(...)` bodies). So on the plain JS backend `js.await` does **not** cross function boundaries — same colored-function model as JS itself.
- **Requirement:** the linker must target **ES2017+**: `scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(ESVersion.ES2017)))`.
- **Scala versions:** Scala 2.12/2.13 got it with Scala.js 1.19.0; **Scala 3 needed Scala 3.8.0**, which bundles Scala.js 1.20.1 ("Scala 3.8.0 upgraded to Scala.js 1.20.1 and added support for js.async and js.await, including JSPI on Wasm"); 3.8.0 shipped a runtime regression — use 3.8.1+. dapr4s is on a 3.10.0-RC1 nightly (`project.scala`), so this is available. Sources: [Scala.js 1.19.0 notes](https://www.scala-js.org/news/2025/04/21/announcing-scalajs-1.19.0/), [scala3 3.8.0 release notes](https://newreleases.io/project/github/scala/scala3/release/3.8.0).
- **Orphan `js.await`:** a `js.await` that is *not* directly inside a `js.async` block. Enabled by `import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait` (an `implicit object ... extends js.AwaitPermit`). The scaladoc is explicit: *"The resulting code will then only link when targeting WebAssembly."* — i.e., **on the plain JS backend, orphan awaits are a link-time error**, not a runtime one. On Wasm they are validated **at run time**: there must be a dynamically enclosing `js.async { ... }` on the call stack **with no JavaScript frame between it and the `js.await`**; otherwise a `WebAssembly.SuspendError` is thrown. Sources: [1.19.0 notes](https://www.scala-js.org/news/2025/04/21/announcing-scalajs-1.19.0/), [JSPI.scala in scala-js repo](https://github.com/scala-js/scala-js/blob/main/library/src/main/scala/scala/scalajs/js/wasm/JSPI.scala).

**Answer to the key question:** on the **JS backend**, `js.await` is strictly local to its `js.async`. Only on the **Wasm backend with JSPI** can a deep call stack of ordinary Scala methods transparently suspend — that is exactly the "new superpower offered by JSPI" the release notes advertise: *"as long as you enter into a `js.async` block somewhere, you can synchronously await Promises in any arbitrary function."* The one hard caveat: any **JS frame** in between (a Scala lambda passed to a JS API — `Promise.then`, timers, an Express/HTTP-server handler, `js.Array.map`) breaks suspension with `WebAssembly.SuspendError`; you must re-enter `js.async` inside every JS-invoked callback.

## 2. WebAssembly backend status & settings

- **Since Scala.js 1.17.0** (2024-09-28): experimental Wasm backend; enable with `scalaJSLinkerConfig ~= (_.withExperimentalUseWebAssembly(true).withModuleKind(ModuleKind.ESModule))`. **`ModuleKind.ESModule` is mandatory**, as is `ModuleSplitStyle.FewestModules` (single module only — no `js.dynamicImport`, no `@JSExportTopLevel` across multiple modules). `link`, `run` and `test` from sbt all work. Source: [Announcing Scala.js 1.17.0](https://www.scala-js.org/news/2024/09/28/announcing-scalajs-1.17.0/).
- **1.18.0 and 1.20.0 were broken and never announced** (superseded by 1.18.1, Jan 2025, and 1.20.1, Sep 2025). **1.19.0** (Apr 2025) added JSPI/orphan-await support and made Wasm output ~15% faster (geomean) than JS output on their benchmarks; **1.20.1** improved Wasm debugging info (names of types/fields/locals/globals) and perf (varargs, collections, startup); **1.20.2** (Jan 2026) is a bugfix release. **1.21.0** (Apr 2026) does not change the experimental status. Sources: [news index](https://www.scala-js.org/news/index.html), [1.20.1 notes](https://www.scala-js.org/news/2025/09/06/announcing-scalajs-1.20.1/), [1.21.0 notes](http://www.scala-js.org/news/2026/04/04/announcing-scalajs-1.21.0/).
- **Still experimental as of 1.21.0**: the docs warn it "may be removed in future minor versions" and may require newer Wasm engines in minor releases. Source: [Experimental WebAssembly backend](https://www.scala-js.org/doc/project/webassembly.html).
- **JSPI answer:** yes — with the Wasm backend + JSPI + `allowOrphanJSAwait`, `js.await` suspends **across arbitrary Scala function boundaries** (deep ordinary call stacks), subject to the no-intervening-JS-frame rule above. Exact names: linker setting **`withExperimentalUseWebAssembly(true)`** (since 1.17.0); import **`scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait`** (since 1.19.0). There is **no** linker flag named `allowOrphanJSAwait` — it's a source-level implicit import; the gate is the Wasm-only link check.

### Restrictions of the Wasm backend (question 4)
- Semantics: *"The Wasm backend is nothing but an alternative backend for the Scala.js language. Its semantics are the same as Scala.js-on-JS"* — the **javalib/JDK API coverage is the same** as the JS backend; there is no extra list of unsupported JDK APIs.
- **`@JSExport` / `@JSExportAll` are silently ignored** (JS cannot call methods on Scala instances; no `toString` interop on instances). `@JSExportTopLevel` works (single module).
- ESModule-only, single module, JS host required (not standalone WASI).
- Performance: ~**30% lower run time** than the JS output on their benchmarks for compute-heavy code (interop-heavy code can be slower); **code size ~2× the JS backend in fullLink mode**.
- **Testing:** `sbt test` works under the Wasm backend (stated since the 1.17.0 announcement: you can link, run and *test*); munit is an ordinary sbt test framework over the standard Scala.js test bridge (which uses top-level exports), so munit works — provided the jsEnv node flags below.
Sources: [webassembly doc page](https://www.scala-js.org/doc/project/webassembly.html), [1.17.0 notes](https://www.scala-js.org/news/2024/09/28/announcing-scalajs-1.17.0/).

## 3. JSPI runtime support

- **Spec status:** JSPI reached W3C Wasm CG **stage 4 (standardized) in April 2025**. Source: [chromestatus 5674874568704000](https://chromestatus.com/feature/5674874568704000), [Intent to Ship thread](https://groups.google.com/a/chromium.org/g/blink-dev/c/w_jCD4gf7Bc).
- **Chrome:** origin trial Chrome 123–136; **shipped by default in Chrome 137** (May 2025). The Scala.js docs list **Chrome 137+ as "full support"** (covers both exnref exception handling and JSPI). (Fun datapoint: [JSPI shipping in Chrome 137 broke urllib3/Pyodide CI](https://github.com/urllib3/urllib3/issues/3598).)
- **Firefox:** Fx 131+ has all required Wasm features for the Scala.js Wasm backend; for JSPI the Scala.js docs still say you must flip `javascript.options.wasm_js_promise_integration` in about:config, while the Chrome intent-to-ship thread states JSPI "is currently shipping in Chrome and in Firefox" — treat Firefox-default-on as recent/uncertain.
- **Safari: no JSPI at all** (18.4+ runs the Wasm backend but *not* `js.async`/orphan-await code).
- **Node.js:**
  - Node 22: **no JSPI** (Scala.js docs require Node 23+ even for the flag; Node 22 also needs `--experimental-wasm-exnref` just for the backend itself).
  - Node 23 / **Node 24** (V8 13.6): JSPI available **behind `--experimental-wasm-jspi`** — "Node 24 was released just before the JSPI feature gate was removed from V8" ([nodejs/node#60014](https://github.com/nodejs/node/pull/60014)).
  - **Node 25** (2025-10-15, V8 14.1): **JSPI enabled by default** — the explicit enable commit was even reverted because the V8 upgrade had already turned it on ([nodejs/node#60014](https://github.com/nodejs/node/pull/60014), [Node 25.0.0 release post](https://nodejs.org/en/blog/release/v25.0.0)).
  - Recommended sbt jsEnv (from the Scala.js docs, needed for Node 23/24): `new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--experimental-wasm-exnref", "--experimental-wasm-jspi", "--experimental-wasm-imported-strings", "--turboshaft-wasm")))`.
- **CI on GitHub Actions:** *plain* Node 22 — **not usable** for JSPI. Usable today with `actions/setup-node` + `node-version: 24` **plus the flags** (passed via the jsEnv args; note `NODE_OPTIONS` does **not** allow V8 `--experimental-wasm-*` flags, so the runner must spawn node with argv flags), or simply **Node 25/26 where it's on by default**. So yes — CI is realistic today, but not with the runner-default Node untouched on Node ≤24.

## 4. (folded into §2 above)

## 5. Alternative patterns for sync-looking APIs on the plain JS backend

### What ecosystem libraries do
- **cats-effect:** `IO#unsafeRunSync()` **throws on Scala.js** ("cannot synchronously await result on JavaScript") the moment it hits an async boundary; JS users get `unsafeRunAsync`/`unsafeToPromise`/`Dispatcher`, and `SyncIO` exists precisely as the "guaranteed-no-async-boundary" sync subset. Sources: [typelevel/cats-effect#529](https://github.com/typelevel/cats-effect/issues/529), [#2846](https://github.com/typelevel/cats-effect/pull/2846).
- **sttp:** the synchronous backends (`DefaultSyncBackend`) are JVM-only; JS gets `FetchBackend` returning `Future`. The general pattern is **abstract over the effect (`F[_]`)** or **platform-split source trees** ("PlatformCompat"): same method names, platform-specific return type (`Identity` on JVM, `Future`/`js.Promise` on JS) — which is an API change in disguise, since shared user code can't treat `Option[T]` and `Future[Option[T]]` uniformly.

### Synchronously waiting for a Promise on plain JS — honest assessment
On a single-threaded JS engine this is **impossible in-thread by design**: run-to-completion means the continuation that would resolve the Promise can never run while you spin/block. The only real escape hatches:

1. **deasync-style event-loop re-entry (N-API/`uv_run`)** — the `deasync` npm package pumps libuv from native code. It's a node-gyp native addon, notoriously fragile across Node versions, can re-enter user code at arbitrary points (reentrancy bugs), and benchmarks ~100× slower than native (synckit's own benchmark: deasync 1367 ms vs synckit 160 ms vs native 13 ms on Node 20). **Not shippable** as a dependency of a Scala.js library.
2. **`Atomics.wait` + worker_threads + SharedArrayBuffer + `receiveMessageOnPort` (the synckit pattern)** — **this genuinely works in Node**. Key facts: `Atomics.wait` **can block the Node.js main thread** (it's only forbidden on the *browser* main thread, where it throws `TypeError`; browser *workers* may block). The pattern: main thread creates a `MessageChannel` + `SharedArrayBuffer`, posts the request to a persistent worker thread; the worker runs the async work (e.g. the `@dapr/dapr` client call) on *its own* event loop, posts the result on the port, `Atomics.notify`s; the main thread wakes from `Atomics.wait` and reads the result **synchronously** via `worker_threads.receiveMessageOnPort`. This is exactly what **[synckit](https://github.com/un-ts/synckit)** (`createSyncFn` / `runAsWorker`) ships, used in production by **eslint-plugin-prettier, eslint-plugin-cspell, eslint-plugin-mdx, Jest snapshot tooling** — proof the pattern is production-grade for *tooling*. Prior art also: [Sam Thorogood's write-up](https://samthor.au/2021/block-nodejs-main-thread/), [jimmywarting/await-sync](https://github.com/jimmywarting/await-sync), Anna Henningsen's [synchronous-worker](https://www.npmjs.com/package/synchronous-worker).
   **Could dapr4s ship this?** Technically yes: pure-JS deps (no native code), Node-only; the stateful `DaprClient` would live in the persistent worker (synckit keeps one worker per `createSyncFn`), with the worker script either hand-written JS or a second Scala.js-compiled module. Real costs: (a) **it blocks the entire Node event loop for the duration of every Dapr call** — server concurrency collapses to fully serial; (b) **deadlock class**: dapr4s apps *receive* callbacks from the sidecar (pubsub, actors, bindings via `DaprAppServer`); if a blocking outbound call's completion ever depends on the blocked main thread serving an inbound request (actor reentrancy, workflow signals), you deadlock; (c) arguments/results must be **structured-clone serializable** — fine for Dapr's JSON-ish payloads, bad for streams; streaming subscriptions can't cross a one-shot sync bridge; (d) per-call overhead ~0.1–1 ms + serialization (synckit ~12× native in its microbenchmark — acceptable next to a sidecar network hop); (e) the worker file must survive consumers' bundlers; (f) **Node-only** (no browser main thread) — acceptable for Dapr, which is server-side anyway.
3. **Busy-wait / microtask draining** — does not exist as a primitive in JS; not an option.

## 6. scala-cli support

- **`//> using jsEmitWasm`** directive and **`--js-emit-wasm`** flag exist **since scala-cli v1.5.2** (experimental, `--power`; "non-ideal user experience should be expected"). Required combo: `//> using platform js`, `//> using jsEmitWasm`, `//> using jsModuleKind es`, `//> using jsModuleSplitStyleStr fewestmodules`. Sources: [scala-cli v1.5.2 release](https://github.com/VirtusLab/scala-cli/releases/tag/v1.5.2), [directives reference](https://scala-cli.virtuslab.org/docs/reference/directives/).
- The v1.5.2 notes only demonstrate **`package`** ("Wrote .../main.js, run it with node ./wasm.js/main.js" — you run node yourself). Crucially, **scala-cli has no documented way to pass Node argv flags (`--experimental-wasm-exnref`, `--experimental-wasm-jspi`) to the node process it spawns for `run`/`test`**, and these flags are not accepted via `NODE_OPTIONS`. Practical consequence for dapr4s (a scala-cli project with munit): Wasm tests under scala-cli are only realistic on **Node 25+** (flags default-on) — and even `run`/`test`-on-wasm under scala-cli should be verified empirically; the supported path for Wasm testing today is **sbt** (full `jsEnv := new NodeJSEnv(NodeJSEnv.Config().withArgs(...))` control) or Mill's `jsEnvConfig`.

---

## Final viability assessment (for a LIBRARY)

### (a) Wasm + JSPI (orphan `js.await`)
**The only mechanism in existence that preserves dapr4s's direct-style `def get(key): Option[T]` on the JS platform without blocking the event loop** — JSPI *suspends* the Wasm stack, the event loop keeps running, so inbound Dapr callbacks still get served. Architecturally it is the exact analogue of virtual-thread parking. Published artifacts are even backend-neutral (`.sjsir`), so one `_sjs1` artifact works — orphan awaits simply fail **at link time** for plain-JS-backend consumers. The costs: consumers are forced onto an **experimental** backend (explicitly removable in future minors), ESModule-only, single-module, `@JSExport`-less, 2× code size; runtime floor is Node 23/24-with-flags or **Node 25+/Chrome 137+ clean, no Safari**; every JS-invoked callback (Dapr JS SDK server handlers!) must re-enter `js.async`, and any stray Scala-lambda-through-JS-API frame turns into a runtime `WebAssembly.SuspendError`; scala-cli test support is shaky (sbt/Mill needed, or Node 25+). **Verdict: viable as an explicitly experimental, opt-in target ("dapr4s on Wasm") — a great demo and a plausible future; not viable in 2026 as the default JS story a library imposes on all users.**

### (b) `Atomics.wait`/synckit-style sync bridge on plain JS
Real, proven (synckit powers eslint-plugin-prettier et al.), pure-JS, shippable — **but only for Node, and it hard-blocks the event loop per call**. For dapr4s's bidirectional model (sidecar calls back into the app for pubsub/actors/workflows) that means serialized throughput at best and genuine deadlocks at worst, plus no streaming and structured-clone-only payloads. **Verdict: viable only for a narrow outbound-only client subset (state get/set, invoke, publish from scripts/CLI tools); not viable as the general dapr4s-on-JS architecture. If pursued, scope it to a clearly-labeled `dapr4s-sync-node` client module.**

### (c) API change (async on JS)
The boring, robust, ecosystem-standard answer (cats-effect forbids `unsafeRunSync` on JS; sttp's sync backends are JVM-only). Platform-split sources giving JS users `Future`/`js.Promise` return types (or a `Dapr().runAsync { ... }` whose body returns within `js.async`, using plain non-orphan `js.await` at the call sites — Scala 3.8+/SJS 1.19+ make that direct-style-*ish*) works on the stable JS backend, every Node/browser version, scala-cli, munit, today. The cost is product-level: dapr4s's signature feature — colorless direct style — does not survive on plain JS; shared user code can't be written once against both signatures. **Verdict: the only production-grade option for the plain JS backend; recommended as the JS baseline, optionally paired with (a) as an experimental Wasm target sharing the same direct-style sources via a small `Awaiter` abstraction (JVM: `CompletableFuture.get()` on a virtual thread; Wasm: orphan `js.await`; plain JS: link-error/unsupported).**

### Sources
- https://www.scala-js.org/news/2025/04/21/announcing-scalajs-1.19.0/
- https://www.scala-js.org/doc/project/webassembly.html
- https://www.scala-js.org/news/2024/09/28/announcing-scalajs-1.17.0/
- https://www.scala-js.org/news/2025/09/06/announcing-scalajs-1.20.1/
- http://www.scala-js.org/news/2026/04/04/announcing-scalajs-1.21.0/
- https://github.com/scala-js/scala-js/blob/main/library/src/main/scala/scala/scalajs/js/wasm/JSPI.scala
- https://chromestatus.com/feature/5674874568704000
- https://developer.chrome.com/release-notes/137
- https://github.com/urllib3/urllib3/issues/3598
- https://github.com/nodejs/node/pull/60014
- https://nodejs.org/en/blog/release/v25.0.0
- https://v8.dev/blog/jspi
- https://github.com/un-ts/synckit
- https://samthor.au/2021/block-nodejs-main-thread/
- https://github.com/jimmywarting/await-sync
- https://github.com/typelevel/cats-effect/issues/529
- https://github.com/typelevel/cats-effect/pull/2846
- https://github.com/VirtusLab/scala-cli/releases/tag/v1.5.2
- https://scala-cli.virtuslab.org/docs/reference/directives/
- https://newreleases.io/project/github/scala/scala3/release/3.8.0

## VERDICT
On the plain Scala.js backend there is no sound way to expose dapr4s's blocking-looking `def get(key): Option[T]` — js.await (Scala.js 1.19.0+, ES2017+, Scala 3.8+) only works lexically inside js.async there, and the only true sync-wait hack (synckit-style worker_threads + SharedArrayBuffer + Atomics.wait + receiveMessageOnPort, proven by eslint-plugin-prettier) is Node-only, blocks the whole event loop per call, and risks deadlock with Dapr's sidecar-callback model. The one mechanism that genuinely preserves direct style is the experimental WebAssembly backend (withExperimentalUseWebAssembly(true), ESModule-only) with JSPI: importing scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait lets js.await suspend across arbitrary Scala frames (link-error on plain JS, WebAssembly.SuspendError if a JS frame intervenes), running flag-free on Node 25+ and Chrome 137+ (Node 23/24 need --experimental-wasm-jspi; Safari unsupported; scala-cli's jsEmitWasm is package-grade only, so tests need sbt/Mill or Node 25+). Recommended posture for a library: ship an async-on-JS API (the cats-effect/sttp precedent) as the stable JS baseline, and optionally offer the direct-style API as an explicitly experimental Wasm+JSPI target via a small platform Awaiter (JVM virtual-thread park / Wasm orphan js.await) — do not bet the default JS story on either the experimental backend or an Atomics.wait bridge.

## BLOCKERS
- Plain JS backend fundamentally cannot block on a Promise in-thread: js.await is restricted to lexically-enclosing js.async blocks; orphan js.await is a hard link-time error when targeting JavaScript (scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait scaladoc: 'will then only link when targeting WebAssembly')
- Wasm backend is officially experimental as of Scala.js 1.21.0 (may be removed/require newer engines in minor releases), ignores @JSExport, ESModule + single module only, ~2x code size — a library cannot impose it as the default JS target
- JSPI runtime floor: Node 25+/Chrome 137+ for flag-free operation; Node 23/24 require --experimental-wasm-jspi (not settable via NODE_OPTIONS); Safari has no JSPI at all
- scala-cli (dapr4s's build tool) supports jsEmitWasm for package only and has no documented way to pass Node flags to its run/test node process — Wasm test runs need sbt/Mill jsEnv config or Node 25+, and scala-cli run/test-on-wasm remains unverified
- Atomics.wait sync-bridge blocks the entire Node event loop per call and can deadlock dapr4s's bidirectional model (sidecar calling back into the app's pubsub/actor/binding handlers while the main thread is blocked); streaming APIs cannot cross the bridge; Node-only
- Wasm+JSPI suspension breaks across JS frames: every JS-invoked callback (e.g. Dapr JS SDK server handlers, Scala lambdas passed to JS APIs) needs its own js.async re-entry or it throws WebAssembly.SuspendError at runtime