//> using target.platform "scala-js"
// Scala.js-only main-scope dependencies, the JS twin of jvm-deps.scala: the `target.platform`
// directive above scopes any `using dep` in this file to the Scala.js platform, so JVM builds
// never resolve them.
//
// No deps yet — the ScalablyTyped-generated facade dependencies (replacing the hand-written
// facades in src/js/internal/facade/) land here in the next phase.
