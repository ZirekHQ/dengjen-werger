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

lazy val root = (project in file("."))
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
  )
