package dapr4s.stgen

import com.olvind.logging
import com.olvind.logging.LogLevel
import org.scalablytyped.converter.{Flavour, Selection}
import org.scalablytyped.converter.internal.{constants, ImportTypingsGenSources, InFolder, IArray}
import org.scalablytyped.converter.internal.importer.{ConversionOptions, EnabledTypeMappingExpansion}
import org.scalablytyped.converter.internal.scalajs.{Name, QualifiedName, Versions}
import org.scalablytyped.converter.internal.ts.TsIdentLibrary

import scala.collection.immutable.{SortedMap, SortedSet}

/** Generates the ScalablyTyped facade SOURCES of dapr4s (the `stFacades` module of build.mill).
  *
  * WHAT: the very code path of sbt-converter's `ScalablyTypedConverterGenSourcePlugin.stImportSources`
  * (`ImportTypingsGenSources.apply`, which lives in the sbt-converter jar but uses no sbt API), driven with the
  * conversion options dapr4s's sbt build set (`stOutputPackage := "dapr4styped"`, `stStdlib := List("es2022")`, the
  * plugin defaults for the rest). Same converter, same options, same input node_modules: the same generated sources, so
  * the embedded facade .sjsir do not change with the build tool.
  *
  * WHY not mill-scalablytyped (com.github.lolgab, 0.4.1 for Mill 1): its worker hard-codes `stdLibs = es6` (dapr4s
  * needs es2022: the @types/node and @dapr/dapr typings use ES2020+ library types) and offers no way to pass the
  * standard library through. RETIRES when a mill-scalablytyped release exposes the stdlib option: then stFacades
  * extends its `ScalablyTyped` trait instead.
  *
  * Arguments: <node_modules> <target dir> <scala version> <scala.js version> <output package> <comma-separated stdlibs>
  * <lib=version>...
  */
object StGen {
  def main(args: Array[String]): Unit = {
    val Array(nodeModules, target, scalaVersion, scalaJsVersion, outputPackage, stdlibs, libs @ _*) = args.toSeq.toArray
    val outPkg = Name(outputPackage)
    val conversion = ConversionOptions(
      useScalaJsDomTypes = true,
      flavour = Flavour.Normal,
      outputPackage = outPkg,
      // sbt-converter: Selection.All for a Scala 3 project, None for Scala 2.
      enableScalaJsDefined = if (scalaVersion.startsWith("3")) Selection.All else Selection.None,
      stdLibs = SortedSet(stdlibs.split(',').toSeq: _*),
      expandTypeMappings = EnabledTypeMappingExpansion.DefaultSelection,
      ignored = SortedSet("typescript"),
      versions = Versions(Versions.Scala(scalaVersion), Versions.ScalaJs(scalaJsVersion)),
      organization = s"org.scalablytyped.${outPkg.unescaped}",
      enableReactTreeShaking = Selection.None,
      enableLongApplyMethod = false,
      privateWithin = None,
      useDeprecatedModuleNames = false,
    )
    val wanted = SortedMap(libs.map { l =>
      val i = l.lastIndexOf('=')
      TsIdentLibrary(l.substring(0, i)) -> l.substring(i + 1)
    }: _*)
    val input = ImportTypingsGenSources.Input(
      fromFolder = InFolder(os.Path(nodeModules)),
      targetFolder = os.Path(target),
      overrideTargetFolder = Map.empty,
      converterVersion = "1.0.0-beta45",
      conversion = conversion,
      wantedLibs = wanted,
      minimize = Selection.None,
      minimizeKeep = IArray.Empty: IArray[QualifiedName],
    )
    val cacheDir = constants.defaultCacheFolder
    ImportTypingsGenSources(
      input,
      logging.stdout.filter(LogLevel.warn),
      parseCacheDirOpt = Some(cacheDir.toNIO.resolve("parse")),
      cacheDirOpt = cacheDir,
    ) match {
      case Right(files) => println(s"StGen: ${files.size} files in $target")
      case Left(errors) =>
        errors.foreach { case (lib, e) => System.err.println(s"StGen: $lib: $e") }
        sys.exit(1)
    }
  }
}
