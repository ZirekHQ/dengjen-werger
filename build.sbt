import com.typesafe.sbt.packager.docker.{Cmd, DockerPlugin}

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / dynverSeparator := "-"
ThisBuild / organization := "org.zirekhq"
ThisBuild / homepage := Some(url("https://github.com/ZirekHQ/dengjen-werger"))
ThisBuild / licenses := List("GPL-3.0-or-later" -> url("https://www.gnu.org/licenses/gpl-3.0.html"))

addCommandAlias("lint", ";scalafmtCheckAll;scalafixAll --check")
addCommandAlias("fix", ";scalafmtAll;scalafixAll")
addCommandAlias("prep", ";fix;test")

lazy val IntegrationTest = config("it") extend Test
lazy val E2e = config("e2e") extend Test

def gitCommit: String =
  sys.env.get("GITHUB_SHA").map(_.take(7)).orElse(
    scala.util.Try(scala.sys.process.Process("git rev-parse --short HEAD").!!.trim).toOption
  ).getOrElse("unknown")

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .configs(IntegrationTest, E2e)
  .settings(
    name := "dengjen-werger",
    inConfig(IntegrationTest)(Defaults.testSettings),
    inConfig(E2e)(Defaults.testSettings),
    scalafixConfigSettings(IntegrationTest),
    scalafixConfigSettings(E2e),
    libraryDependencies ++= Dependencies.all,
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum := true,
    coverageExcludedPackages := "werger\\.Main",
    coverageExcludedFiles := ".*/adapters/db/Db",
    Compile / mainClass := Some("werger.Main"),
    buildInfoKeys := Seq[BuildInfoKey](
      name, version, scalaVersion,
      BuildInfoKey.action("gitCommit")(gitCommit)
    ),
    buildInfoPackage := "werger",
    dockerBaseImage := "eclipse-temurin:21-jre-alpine",
    dockerExposedPorts := Seq(8080),
    dockerUpdateLatest := true,
    // JavaAppPackaging's launch script is bash, which the alpine base doesn't ship.
    dockerCommands := dockerCommands.value.flatMap {
      case cmd @ Cmd("FROM", _*) => List(cmd, Cmd("RUN", "apk", "add", "--no-cache", "bash"))
      case other                 => List(other)
    },
  )
