ThisBuild / scalaVersion := "3.9.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

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
