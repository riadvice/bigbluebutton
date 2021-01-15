package org.bigbluebutton.build

import sbt._
import Keys._

object Dependencies {

  object Versions {
    // Scala
    val scala = "2.13.4"
    val junit = "5.7.0"
    val scalactic = "3.2.3"

    // Libraries
    val akkaVersion = "2.6.10"
    val akkaHttpVersion = "10.2.2"
    val logback = "1.2.3"

    // Apache Commons
    val lang = "3.11"
    val codec = "1.15"

    // BigBlueButton
    val bbbCommons = "0.0.21-SNAPSHOT"
    val bbbFsesl = "0.0.8-SNAPSHOT"

    // Test
    val scalaTest = "3.1.0"
    val akkaTestKit = "2.6.0"
  }

  object Compile {
    val scalaLibrary = "org.scala-lang" % "scala-library" % Versions.scala
    val scalaCompiler = "org.scala-lang" % "scala-compiler" % Versions.scala

    val akkaActor = "com.typesafe.akka" % "akka-actor_2.13" % Versions.akkaVersion
    val akkaSl4fj = "com.typesafe.akka" % "akka-slf4j_2.13" % Versions.akkaVersion
    val akkaStream = "com.typesafe.akka" %% "akka-stream" % Versions.akkaVersion

    val akkaHttp = "com.typesafe.akka" %% "akka-http" % Versions.akkaHttpVersion
    val akkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % Versions.akkaHttpVersion

    val logback = "ch.qos.logback" % "logback-classic" % Versions.logback
    val commonsCodec = "commons-codec" % "commons-codec" % Versions.codec

    val apacheLang = "org.apache.commons" % "commons-lang3" % Versions.lang

    val bbbCommons = "org.bigbluebutton" % "bbb-common-message_2.13" % Versions.bbbCommons excludeAll (
      ExclusionRule(organization = "org.red5"))
    val bbbFseslClient = "org.bigbluebutton" % "bbb-fsesl-client" % Versions.bbbFsesl
  }

  object Test {
    val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest % "test"
    val junit = "org.junit.jupiter" % "junit-jupiter-api" % Versions.junit % "test"
    val scalactic = "org.scalactic" % "scalactic_2.13" % Versions.scalactic % "test"
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % Versions.akkaTestKit % "test"

    // https://mvnrepository.com/artifact/com.typesafe.akka/akka-http-testkit
    val akkaHttpTestkit =  "com.typesafe.akka" %% "akka-http-testkit" % "10.2.2" % "test"

  }

  val testing = Seq(
    Test.scalaTest,
    Test.junit,
    Test.scalactic,
    Test.akkaTestKit,
    Test.akkaHttpTestkit)

  val runtime = Seq(
    Compile.scalaLibrary,
    Compile.scalaCompiler,
    Compile.akkaActor,
    Compile.akkaSl4fj,
    Compile.akkaStream,
    Compile.logback,
    Compile.commonsCodec,
    Compile.apacheLang,
    Compile.bbbCommons,
    Compile.bbbFseslClient,
    Compile.akkaHttp,
    Compile.akkaHttpSprayJson) ++ testing
}
