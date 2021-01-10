package org.bigbluebutton.build

import sbt._
import Keys._

object Dependencies {

  object Versions {
    // Scala
    val scala = "2.12.10"
    val junit = "5.6.0-M1"
    val scalactic = "3.1.0"

    // Libraries
    val akkaVersion = "2.6.0"
    val gson = "2.8.6"
    val jackson = "2.10.1"
    val logback = "1.2.3"
    val quicklens = "1.4.12"
    val spray = "1.3.5"

    // Apache Commons
    val lang = "3.9"
    val codec = "1.14"

    // BigBlueButton
    val bbbCommons = "0.0.20-SNAPSHOT"

    // Test
    val scalaTest = "3.1.0"
    val mockito = "3.2.0"
    val akkaTestKit = "2.6.0"
  }

  object Compile {
    val scalaLibrary = "org.scala-lang" % "scala-library" % Versions.scala
    val scalaCompiler = "org.scala-lang" % "scala-compiler" % Versions.scala

    val akkaActor = "com.typesafe.akka" % "akka-actor_2.12" % Versions.akkaVersion
    val akkaSl4fj = "com.typesafe.akka" % "akka-slf4j_2.12" % Versions.akkaVersion

    val googleGson = "com.google.code.gson" % "gson" % Versions.gson
    val jacksonModule = "com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson
    val quicklens = "com.softwaremill.quicklens" %% "quicklens" % Versions.quicklens
    val logback = "ch.qos.logback" % "logback-classic" % Versions.logback
    val commonsCodec = "commons-codec" % "commons-codec" % Versions.codec
    val sprayJson = "io.spray" % "spray-json_2.12" % Versions.spray

    val apacheLang = "org.apache.commons" % "commons-lang3" % Versions.lang

    val bbbCommons = "org.bigbluebutton" % "bbb-common-message_2.12" % Versions.bbbCommons
  }

  object Test {
    val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest % "test"
    val junit = "org.junit.jupiter" % "junit-jupiter-api" % Versions.junit % "test"
    val mockitoCore = "org.mockito" % "mockito-core" % Versions.mockito % "test"
    val scalactic = "org.scalactic" % "scalactic_2.12" % Versions.scalactic % "test"
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % Versions.akkaTestKit % "test"
  }

  val testing = Seq(
    Test.scalaTest,
    Test.junit,
    Test.mockitoCore,
    Test.scalactic,
    Test.akkaTestKit)

  val runtime = Seq(
    Compile.scalaLibrary,
    Compile.scalaCompiler,
    Compile.akkaActor,
    Compile.akkaSl4fj,
    Compile.googleGson,
    Compile.jacksonModule,
    Compile.quicklens,
    Compile.logback,
    Compile.commonsCodec,
    Compile.sprayJson,
    Compile.apacheLang,
    Compile.bbbCommons) ++ testing
}