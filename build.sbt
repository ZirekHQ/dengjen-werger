ThisBuild / scalaVersion := "3.9.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalacOptions += "-Wunused:all"

lazy val root = (project in file("."))
  .settings(
    name := "dengjen-werger",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.7.1",
      "org.http4s"    %% "http4s-ember-server" % "0.23.37",
      "org.http4s"    %% "http4s-ember-client" % "0.23.37",
      "org.http4s"    %% "http4s-circe"        % "0.23.37",
      "org.http4s"    %% "http4s-dsl"          % "0.23.37",
      "io.circe"      %% "circe-generic"       % "0.14.16",
      "org.tpolecat"  %% "skunk-core"          % "1.0.0",
      "com.github.jwt-scala" %% "jwt-circe"    % "11.0.4",
      "org.typelevel" %% "munit-cats-effect"   % "2.2.0" % Test
    )
  )
