# dapr4s Phase-0 Wasm component spike: build log and evidence

> Source: local spike repo `/home/ondra/Projects/dapr4s-wasm-spike` (not published), commits 618849d..26ccc06; scala-wasm sources at `e24a46b6f` (1.22.1-wasm.6) and `1609fb71a` (default branch `scala-wasm`, 1.22.1-wasm.7-SNAPSHOT); scala-wasm/scala3 branch `wasm` (17a0deeb, 987adced); dapr-wasm-components v0.7.0 and main@17b2710
> Collected: 2026-10-12
> Published: 2026-10-12

Primary evidence collected while running Phase 0 of docs/WASM-COMPONENTS-PROPOSAL.md. Exact commands and
key output lines, verbatim where quoted.

## Versions

- sbt 1.13.0 (launcher 2.0.8), OpenJDK 21.0.12.1.
- `io.github.scala-wasm:sbt-scalajs:1.22.1-wasm.6`. It is the newest on Maven Central. The newest GitHub release is
  `v1.22.1-wasm.5` (2026-09-14). There is no GitHub tag for wasm.6. Its release commit is `e24a46b6f "1.22.1-wasm.6"`
  (merged as scala-wasm/scala-wasm#266).
- The `scala` branch of scala-wasm/wit-bindgen is archived: README "This fork is archived in favor of
  scala-wasm/wit-bindgen-scala". The 1.22.1-wasm.6 sbt plugin runs the binary `wit-bindgen-scala`
  (ScalaJSPluginInternal: `Seq("wit-bindgen-scala", witDir, "--out-dir", ..., "--world", w, "--base-package", p)`),
  output into `src_managed/main/wit-bindgen`. We installed `cargo install wit-bindgen-scala --version 0.1.0`.
- wasm-tools 1.261.0, wasmtime 49.0.2, wkg 0.16.1, wac 0.10.0, dapr CLI 1.15.1, daprd 1.17.7, redis:7-alpine.
- Scala 3 nightly `3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` (dapr4s master = v3.0.0) with dapr4s's 6 flags.
- Scala 3 artifacts published by scala-wasm: `io.github.scala-wasm:scala3-compiler_3`, `scala3-library_sjs1_3`,
  `scala-library`, `scalajs-scalalib_2.13`, all version `3.8.3-RC1-wasm.4` only (2026-05-27). They come from
  scala-wasm/scala3 branch `wasm`, 2 commits on top of scala/scala3: 17a0deeb "Add scala-wasm WIT IR support to
  ScalaJS backend" (+2196 lines: backend/sjs/WitCodeGen.scala, PrepJSInterop, JSCodeGen; library-js Symbol,
  ArrayBuilder, Buffer, BoxesRunTime) and 987adced "Release Scala wasm 3.8.3-RC1-wasm.4".

## Step 1: trivial component on the nightly

`sbt hello/fastLinkJS` with plugin 1.22.1-wasm.6, `ModuleKind.WasmComponent`, `withUseWebAssembly(true)`,
`ESVersion.ES2022`, module initializer exported as `wasi:cli/run@0.2.0#run`:

```
[error] Uses JS interop with with a Wasm-only module kind:
[error]   at https://raw.githubusercontent.com/scala-js/scala-js/v1.20.2/scalalib/overrides/scala/runtime/BoxesRunTime.scala:52:46: (x === y)
[error]   called from static scala.runtime.BoxesRunTime.equals(java.lang.Object,java.lang.Object)boolean
...
[error]   called from constructor scala.util.hashing.MurmurHash3$.<init>()void
[error]   called from scala.scalajs.js.JavaScriptException.hashCode()int
[error]   dispatched from java.lang.Object.hashCode()int
[error]   called from java.lang.Object.toString()java.lang.String
```

Classpath had both `org.scala-js:scalajs-scalalib_2.13:3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` (3651 sjsir +
the stdlib TASTy) and `io.github.scala-wasm:scalajs-scalalib_2.13:2.13.18+1.22.1-wasm.6` (2900 sjsir), plus
mainline `org.scala-js:scalajs-javalib:1.20.2` next to the fork's javalib. The nightly scalalib came first.
- Excluding the nightly scalalib breaks compilation: `package scala.compiletime does not have a member method summonFrom`.
- Overlay fix: the `.sjsir` of `scala/runtime/BoxesRunTime*`, `scala/Symbol*`,
  `scala/collection/mutable/ArrayBuilder*` and `scala/collection/mutable/Buffer*` from
  `io.github.scala-wasm:scalajs-scalalib_2.13:3.8.3-RC1-wasm.4` go first on `Compile / fullClasspath`. The
  linker's FileIRLoader keeps the first IR file per class ("Remove duplicates. Just like the JVM"). Result:
  `[success]`, a 121 KB component `world root { export wasi:cli/run@0.2.0; }`, and `wasmtime run` exits 0. A
  thrown exception gives a non-zero trap.
- IR compatibility: the 1.22.1-wasm.6 linker (`binaryEmitted = "1.22+wasm"`) read the nightly's IR without complaint.

## Steps 2-3: bindings and the counter app

WIT: `wit/world.wit` = `package dapr4s-spike:counter; world app { include dapr-wasm-components:interfaces/app@0.7.0; }`,
plus the upstream `wkg-config.toml` (`[namespace_registries] dapr-wasm-components = { registry = "sideeffffect",
metadata = { preferredProtocol = "oci", "oci" = { registry = "ghcr.io", namespacePrefix = "sideeffffect/" } } }`).
`wkg wit fetch --config wkg-config.toml` writes `wit/deps/dapr-wasm-components-interfaces-0.7.0/package.wit` and a
`wkg.lock` with digest `sha256:b46e15f3e360c3a8c1a6fc8ac5b732fb8476f9092fbb356dabac1b8aa20b0344`. Before
2026-10-12 evening it failed with `Authentication failure ... requested access to the resource is denied`,
because the package had only been published under the flat ref `ghcr.io/sideeffffect/dapr-wasm-components-interfaces:0.7.0`.
Since then it is also at `ghcr.io/sideeffffect/dapr-wasm-components/interfaces:0.7.0`.

Generated code (wit-bindgen-scala 0.1.0): imports become `package object <iface>` with
`@WitImport(WitScope(...), "fn") def fn(@WitName(..) ...): wit.Result[..] = scala.scalajs.wit.native`. Records are
`final class` + companion, variants are `sealed trait` + `final class`/`object` cases with `@WitName`, and enums
are `sealed trait` + objects. For the export interfaces it generates **types only**. Export functions are
hand-written `@WitExport(WitScope(...), "fn")` methods on static objects, and every parameter needs
`@WitName` (else `missing literal @WitName on value request`). `wit.Result` is not sealed, so a Scala 3
type-test pattern on `wit.Err[E]` gives `E092 ... cannot be checked at runtime`; use `.fold`.

Attempt A, bindings + app compiled by the nightly with all dapr4s flags: compiles with zero errors and zero
warnings (`-Wconf:any:error`). `fastLinkJS` reports `[success]` but writes an **empty** output directory. The
app's `.sjsir` has no WIT information (`b"on-invoke" in bytes == False`): mainline Scala 3 has no WIT
codegen, and imports are lowered at the call site (scala-wasm `GenJSCode`:
`sym.hasAnnotation(WitImportAnnotation) => genWitNativeMemberCall`).

Attempt B, bindings compiled by scala-wasm's Scala 3 fork 3.8.3-RC1-wasm.4: 96 errors, e.g.
`Field 'value' has type 'scala.scalajs.wit.Option[Array[scala.scalajs.wit.unsigned.UByte]]' which is not compatible with the Component Model.`
(68× wit.Option; also every enum: Concurrency, Consistency, TransactionOperation, RuntimeStatus,
ActorStateOperationType, HttpVerb; 22 alias fields).

Attempt C (works): the bindings and the export glue are compiled by Scala 2.13.18 + scala-wasm's compiler plugin.
Plain forwarders (`StateForward.get/save/...`) are compiled on the same side. Scala 3 nightly code depends on
those projects (`dependsOn` across 2.13/3). It constructs and matches the generated classes directly and
implements a 2.13 trait (`InvocationHandler`).

Reactor entry: module initializers run only behind a configured export (linker Emitter: "Wasm Component module
initializers require a configured Wasm Component module initializer export."). `scala.scalajs.reflect.Reflect`
uses `js.Function`. So the export glue references a fixed FQN object, compiled against a `Provided` stub, and
the application defines it.

Next link: `scala.NotImplementedError: an implementation is missing` at
`org.scalajs.linker.backend.wasmemitter.canonicalabi.InteropEmitter$.genWitExportDef(InteropEmitter.scala:199)`, where
`case Some(paramsOffset) => ??? // TODO read params from linear memory`. The import side has
`case Some(offset) => // TODO : put params onto linear memory` and emits nothing. Both are unchanged on
the default branch `scala-wasm` @1609fb71a. Flat-param counts in world `app` 0.7.0 above MAX_FLAT_PARAMS=16:
exports `pubsub-callback.on-topic-event` 22 and `actors-callback.on-timer` 17; imports `jobs.schedule` 24,
`conversation.converse` 28, `actors.register-reminder` 18 and `actors.register-timer` 21. An unpatched import
call to `jobs.schedule` fails at component encoding: `func 203 failed to validate ... type mismatch: expected i32 but
nothing on stack`. Patch for the export side (local linker `1.22.1-wasm.6-spike1`): add param `params_ptr: i32`,
lower `savedStackPointer` to it when smaller (frees the cabi_realloc'd tuple in post-return), then for each param
`genAlignTo(alignment)`, `genLoadMemory(tpe)`, `ptr += elemSize`. Override with
`Global / scalaJSLinkerImpl / fullClasspath` → `io.github.scala-wasm:scalajs-linker_2.12:1.22.1-wasm.6-spike1`.

Component: validates. Authority (`wasm-tools component wit`): imports `state-read` {get}, `state` {save}, and
`types`, `invocation`, `configuration-read` with zero functions. Exports all 7 callback interfaces.

## Step 4: compose and run

`compose.sh app.wasm --tag 0.7.0` (wac) failed:
`mismatched instantiation argument 'dapr-wasm-components:interfaces/invocation-callback@0.7.0' ... instance is missing expected list export 'metadata'`.
The linker's synthesized component type had `headers: list<tuple<string, string>>` instead of `use types.{metadata}`.
`WasmFeatures.withWitDirectory/withWitWorld` are not read by the linker. Its JVM processor only runs
`wasm-tools component new` on the core module, whose `component-type` section the linker writes from IR.
Fix: extract the largest core module from the component (`component new` adds `wit-component:shim`/`fixups`
modules), `wasm-tools strip --delete 'component-type.*'`,
`wasm-tools component embed --encoding utf16 wit --world app`, then `wasm-tools component new`.
With the default `--encoding utf8`, `request.method` arrived as `潣湵整r` (bytes \xe6\xbd\xa3...): UTF-8
"counter" read as UTF-16 code units.

`dapr run --app-id spike --app-port 18087 --dapr-http-port 3587 --dapr-grpc-port 50087 --resources-path components -- wasmtime serve -S cli --addr 127.0.0.1:18087 --env DAPR_HTTP_PORT=3587 composed.wasm`
(state.redis on localhost:26479). `dapr invoke --app-id spike --method counter --verb POST` printed `1`, then
`2`. Redis `HGETALL spike||counter` → `data 2 version 2`.

compose.sh (v0.7.0 = main@17b2710 for compose.sh/compose.wac) exits 1 on success: `tmp` is assigned in
`resolve()` inside `$(...)`, so the top-level EXIT trap `[[ -n "$tmp" ]] && rm -rf "$tmp"` returns 1. The pulled
temp dirs leak.

## Step 5: dapr4s on top

`com.github.sideeffffect::dapr4s::3.0.0` (dapr4s_sjs1_3). Its POM deps: scala3-library_sjs1_3 (nightly),
`org.scala-js:scalajs-library_2.13:1.22.0`, scala-java-time_sjs1_3 2.6.0, scalablytyped-runtime_sjs1_3 2.4.2,
scalajs-dom_sjs1_3 2.8.1. The mainline scalajs-library/javalib were excluded (classpath order otherwise decides).
`WasmStateInstance extends StateCapability.Instance` and `WasmStateCapability extends StateCapability` compile
under all dapr4s flags. One CC error, fixed with the same documented cast dapr4s's walls use:
`Found: WasmStateInstance^{<any created in method instance ...>} Required: StateCapability.Instance^{WasmStateCapability.this}`.
The app links with zero JS errors. The run printed 1, 2. Error probes:
`missing-store` → `ComponentNotFoundException: State no-such-store` (the provider's `store-not-found` payload
was daprd's raw body: `HTTP 400: {"errorCode":"ERR_STATE_STORE_NOT_FOUND","message":"state store no-such-store is not found",...}`);
`stale-etag` → `ETagMismatchException: ETag mismatch for key 'counter' (provided: 999999)`.
Authority: still only `state-read.get` and `state.save`, although all 11 state operations are implemented.

Reachability probes (one component each, through the dapr4s layer):

```
baseline: LINKS            duration: LINKS          finiteDuration: LINKS
stringFormat: LINKS        uuidFromString: LINKS
currentTimeMillis: FAILS   javalib/.../java/lang/System.scala:74:16: global:Date["now"]()
println: FAILS             javalib/.../java/lang/System.scala:459:18: (typeof global:console)
instant / instantNow / workflowSnapshot / zoneOffset: FAILS
   scala-java-locales core/js/.../locales/DefaultLocale.scala:20:47: global:window
   <- locales.DefaultLocale$.platformLocale <- java.util.Locale$.<init> <- Locale.getDefault
   <- DateTimeFormatterBuilder.toFormatter <- DateTimeFormatter$.ISO_INSTANT <- Instant$.parse
uuidRandom: FAILS          Referring to non-existent class java.security.SecureRandom
```

With scala-java-time-tzdb added explicitly, even trivial probes failed. `js.WrappedDictionary` became an
instantiated class, so `List.groupBy` → `IterableOps.newSpecificBuilder` dispatches into
`WrappedDictionary$WrappedDictionaryBuilder.<init>` → `Dictionary.scala:60:6: {}`, inside
`dapr4s.DaprAppValidation$.duplicates`.

## Step 6: exports from a library

The 15 `@WitExport` functions live in the `wall` library. With `exportJars := true` they come from
`wall_sjs1_2.13-0.1.0-SNAPSHOT.jar`, and the component still exports all 7 interfaces. Without the app's
`ComponentRoot`: `Referring to non-existent method dapr4s.wasmspike.ComponentRoot$.component()dapr4s.wasmspike.DaprComponent`.

## Step 7: measurements

```
variant       app KB composed KB compile s  cold start ms (uncached, -C cache=n)  1st via sidecar ms  warm median ms
app              501       2316      0.32   328 / 319 / 355                          1.8                1.46
app-opt          200       2015      0.34   365 / 348 / 312                          2.1                1.43
appDapr4s       1415       3230      0.81   864 / 798 / 848                          2.9                2.42
appDapr4s-opt    498       2312      0.87   827 / 782 / 787                          2.9                2.44
cached (wasmtime default cache): app 25 ms, appDapr4s 37-42 ms
providers: http-outbound 1,124,931 B; http-inbound 724,584 B
```
