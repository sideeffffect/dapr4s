//> using target.platform "scala-js"
// Scala.js-only TEST-scope dependencies — the JS twin of jvm-test-deps.test.scala, currently
// empty on purpose (munit and upickle are cross-platform and live in project.scala).
//
// When a JS-only test dependency is needed, add it here as a plain `//> using dep` (NOT
// `test.dep`): `test.dep` directives are not platform-scoped, so the test scoping must come
// from the `.test.scala` filename suffix, with the platform scoping coming from the
// `target.platform` directive above.
