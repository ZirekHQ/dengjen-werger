import sbt._

object Dependencies {
  object V {
    val catsEffect = "3.7.1"
    val http4s = "0.23.37"
    val circe = "0.14.16"
    val skunk = "1.0.0"
    val jwtScala = "11.0.4"
    val munitCE = "2.2.0"
  }

  private val http4sOrg = "org.http4s"

  val all: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-effect" % V.catsEffect,
    http4sOrg %% "http4s-ember-server" % V.http4s,
    http4sOrg %% "http4s-ember-client" % V.http4s,
    http4sOrg %% "http4s-circe" % V.http4s,
    http4sOrg %% "http4s-dsl" % V.http4s,
    "io.circe" %% "circe-generic" % V.circe,
    "org.tpolecat" %% "skunk-core" % V.skunk,
    "com.github.jwt-scala" %% "jwt-circe" % V.jwtScala,
    "org.typelevel" %% "munit-cats-effect" % V.munitCE % "it,e2e,test"
  )
}
