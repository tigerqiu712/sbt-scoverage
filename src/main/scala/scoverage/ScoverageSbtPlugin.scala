package scoverage

import sbt._
import sbt.Keys._
import scoverage.report._
import scala.language.postfixOps

object ScoverageSbtPlugin extends ScoverageSbtPlugin

class ScoverageSbtPlugin extends sbt.Plugin {

  // This version number should match that imported in build.sbt
  val ScoverageGroupId = "org.scoverage"
  val ScalacScoveragePluginVersion = "0.98.2"
  val ScalacScoverageArtifactName = "scalac-scoverage-plugin"

  object ScoverageKeys {
    val scoverageVersion = SettingKey[String]("scoverage-version")
    val excludedPackages = SettingKey[String]("scoverage-excluded-packages")
  }

  import ScoverageKeys._

  lazy val scoverage = config("scoverage")
  lazy val scoverageTest = config("scoverage-test") extend scoverage

  lazy val instrumentSettings = {
    inConfig(scoverage)(Defaults.compileSettings) ++
    inConfig(scoverageTest)(Defaults.testSettings) ++
      Seq(
        ivyConfigurations ++= Seq(scoverage hide, scoverageTest hide),

        libraryDependencies +=
          ScoverageGroupId %% ScalacScoverageArtifactName % ScalacScoveragePluginVersion % scoverage.name,

        sources in scoverage <<= (sources in Compile),
        sourceDirectory in scoverage <<= (sourceDirectory in Compile),
        resourceDirectory in scoverage <<= (resourceDirectory in Compile),
        excludedPackages in scoverage := "",

        scalacOptions in scoverage <++= (name in scoverage,
          baseDirectory in scoverage,
          crossTarget in scoverageTest,
          update,
          excludedPackages in scoverage) map {
          (n, b, target, report, excluded) =>
            val scoverageDeps = report matching configurationFilter(scoverage.name)
            scoverageDeps.find(_.getAbsolutePath.contains(ScalacScoverageArtifactName)) match {
              case None => throw new Exception(s"Fatal: $ScalacScoverageArtifactName not in libraryDependencies")
              case Some(classpath) =>
                Seq(
                  "-Xplugin:" + classpath.getAbsolutePath,
                  "-Yrangepos",
                  "-P:scoverage:excludedPackages:" + Option(excluded).getOrElse(""),
                  "-P:scoverage:dataDir:" + target
                )
            }
        },

        sources in scoverageTest <<= (sources in Test),
        sourceDirectory in scoverageTest <<= (sourceDirectory in Test),
        unmanagedResources in scoverageTest <<= (unmanagedResources in Test),
        resourceDirectory in scoverageTest <<= (resourceDirectory in Test),

        externalDependencyClasspath in scoverage <<= Classpaths
          .concat(externalDependencyClasspath in scoverage, externalDependencyClasspath in Compile),
        externalDependencyClasspath in scoverageTest <<= Classpaths
          .concat(externalDependencyClasspath in scoverageTest, externalDependencyClasspath in Test),

        internalDependencyClasspath in scoverage <<= (internalDependencyClasspath in Compile),
        internalDependencyClasspath in scoverageTest <<= (internalDependencyClasspath in Test, internalDependencyClasspath in scoverageTest, classDirectory in Compile) map {
          (testDeps, scoverageDeps, oldClassDir) =>
            scoverageDeps ++ testDeps.filter(_.data != oldClassDir)
        },

        testOptions in scoverageTest <+= testsCleanup,

        // make scoverage config the same as scoverageTest config
        test in scoverage <<= (test in scoverageTest)
      )
  }

  /** Generate hook that is invoked after each tests execution. */
  def testsCleanup = {
    (crossTarget in scoverageTest,
      baseDirectory in Compile,
      scalaSource in Compile,
      definedTests in scoverageTest,
      streams in Global) map {
      (crossTarget,
       baseDirectory,
       compileSourceDirectory,
       definedTests,
       streams) =>
        if (definedTests.isEmpty) {
          Tests.Cleanup {
            () => {}
          }
        } else {
          Tests.Cleanup {
            () =>

              val coverageFile = IOUtils.coverageFile(crossTarget)
              val measurementFiles = IOUtils.findMeasurementFiles(crossTarget)

              streams.log.info(s"Reading scoverage profile file [$coverageFile]")
              streams.log.info(s"Reading scoverage measurement files [$measurementFiles]")

              val coverage = IOUtils.deserialize(getClass.getClassLoader, coverageFile)
              val measurements = IOUtils.invoked(measurementFiles)
              coverage.apply(measurements)

              coverageFile.delete()
              for ( file <- measurementFiles ) file.delete()

              val coberturaDirectory = crossTarget / "coverage-report"
              val scoverageDirectory = crossTarget / "scoverage-report"

              coberturaDirectory.mkdirs()
              scoverageDirectory.mkdirs()

              streams.log.info("Generating Cobertura XML report...")
              new CoberturaXmlWriter(baseDirectory, coberturaDirectory).write(coverage)

              streams.log.info("Generating Scoverage XML report...")
              new ScoverageXmlWriter(compileSourceDirectory, scoverageDirectory, false).write(coverage)

              streams.log.info("Generating Scoverage Debug report...")
              new ScoverageXmlWriter(compileSourceDirectory, scoverageDirectory, true).write(coverage)

              streams.log.info("Generating Scoverage HTML report...")
              new ScoverageHtmlWriter(compileSourceDirectory, scoverageDirectory).write(coverage)

              streams.log.info("Scoverage reports completed")
              ()
          }
        }
    }
  }
}
