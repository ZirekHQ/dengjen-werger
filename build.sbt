ThisBuild / scalaVersion := "3.9.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

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
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.7.1",
      "org.http4s"    %% "http4s-ember-server" % "0.23.37",
      "org.http4s"    %% "http4s-ember-client" % "0.23.37",
      "org.http4s"    %% "http4s-circe"        % "0.23.37",
      "org.http4s"    %% "http4s-dsl"          % "0.23.37",
      "io.circe"      %% "circe-generic"       % "0.14.16",
      "org.tpolecat"  %% "skunk-core"          % "1.0.0",
      "com.github.jwt-scala" %% "jwt-circe"    % "11.0.4",
      "org.typelevel" %% "munit-cats-effect"   % "2.2.0" % "it,e2e,test"
    )
  )
