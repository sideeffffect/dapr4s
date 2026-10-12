addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter" % "1.0.0-beta45")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")

// sbt-converter pulls scala-parser-combinators 2.x into the meta-build, sbt-pgp (via
// sbt-ci-release) and sbt's ssl-config 1.x. They never meet at runtime in a way that matters (each
// plugin only parses its own input), so the early-semver eviction error is downgraded to a warning.
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-parser-combinators" % VersionScheme.Always
