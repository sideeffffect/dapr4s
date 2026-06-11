//> using target.platform "scala-js"
package dapr4s.internal.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import typings.expressServeStaticCore.mod.{Express, Handler}

// ---------------------------------------------------------------------------
// The ONE hand-written facade that survives the ScalablyTyped migration.
//
// Everything else in the JS interop layer comes from the generated `typings.*`
// packages (see js-deps.scala). This file exists because ScalablyTyped cannot
// express two members of the express module object:
//
//   1. `typings.express.mod.apply()` calls through the module root captured as
//      `@JSImport("express", JSImport.Namespace)`. express is a classic
//      CommonJS module (`module.exports = createApplication`); under Node ES
//      modules an `import * as ns` namespace object is NEVER callable, so the
//      ST entry point throws `TypeError: ns is not a function` at runtime
//      (verified in the ScalablyTyped spike under `jsModuleKind es`, the
//      Wasm/JSPI production target).
//   2. `typings.express.mod.text` lost its type to a converter limitation —
//      the generated member is `Any` with an inline `/* import warning:
//      ResolveTypeQueries.resolve Loop while resolving typeof bodyParser.text
//      */` — so the middleware factory cannot be invoked as typed.
//
// Both members live on the SAME runtime object: the CJS default export is the
// callable `createApplication` function which also carries the middleware
// factories (`text`, `json`, ...) as properties. A `JSImport.Default` binding
// yields exactly that object under both module kinds (under `commonjs` via
// Scala.js's `$moduleDefault` interop helper, under `es` via Node's CJS→ESM
// default interop), hence one native object with `apply` + `text`, typed with
// the ScalablyTyped-generated `Express`/`Handler` types so the rest of the
// code stays on the generated surface. Runtime-verified against a real
// sidecar by the e2e smoke run (see docs/DESIGN.md).
// ---------------------------------------------------------------------------

/** The express module's CJS default export (`lib/express.js`): callable application factory with the middleware
  * factories as properties. See the file header for why this cannot come from ScalablyTyped.
  *
  * The `apply` member denotes calling the imported value itself as a function (standard Scala.js facade rule for
  * members named `apply` without `@JSName`).
  */
@js.native
@JSImport("express", JSImport.Default)
private[internal] object ExpressModule extends js.Object:

  /** `express()` — create an application (`lib/express.js` `createApplication`). */
  def apply(): Express = js.native

  /** `express.text(options)` — the re-exported body-parser text middleware (`exports.text = bodyParser.text`). With
    * `type` set to the catch-all media range (star-slash-star) it captures every request body as a raw string in
    * `req.body`, leaving JSON parsing to our dispatch code (mirroring the JVM server's raw `readBody`). Note
    * body-parser's quirk: for requests it skips (no body, or no `Content-Type` to match), it sets `req.body = {}`, not
    * a string — see `DaprAppServer.readBody`.
    */
  def text(options: ExpressTextOptions): Handler = js.native

/** Options for [[ExpressModule.text]] (body-parser `lib/types/text.js`).
  *
  * @param `type`
  *   the media type(s) to match (via type-is); the catch-all media range (star-slash-star) matches any present
  *   `Content-Type`
  * @param limit
  *   maximum body size in bytes when passed as a number (`typeof opts.limit === 'number'` skips `bytes.parse`)
  */
private[internal] final class ExpressTextOptions(
    val `type`: js.UndefOr[String] = js.undefined,
    val limit: js.UndefOr[Double] = js.undefined,
) extends js.Object
