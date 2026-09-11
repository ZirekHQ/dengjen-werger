ThisBuild / scalaVersion := "3.3.4"

lazy val root = (project in file("."))
  .settings(
    name := "dengjen-werger",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.5.4",
      "org.http4s"    %% "http4s-ember-server" % "0.23.27",
      "org.http4s"    %% "http4s-ember-client" % "0.23.27",
      "org.http4s"    %% "http4s-circe"        % "0.23.27",
      "org.http4s"    %% "http4s-dsl"          % "0.23.27",
      "io.circe"      %% "circe-generic"       % "0.14.9",
      "org.tpolecat"  %% "skunk-core"          % "0.6.4",
      "com.github.jwt-scala" %% "jwt-circe"    % "10.0.1",
      "org.typelevel" %% "munit-cats-effect"   % "2.0.0" % Test
    )
  )
