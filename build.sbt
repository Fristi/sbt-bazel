import java.nio.file.{Files, Paths, StandardCopyOption}

import sbt.Keys._

organization := "io.dhlparcel"
name := "sbt-bazel"
sbtPlugin := true
publishMavenStyle := true

skip in publish := false
skip in publishLocal := false

libraryDependencies ++= Seq(
  "io.get-coursier" %% "coursier" % "2.0.13",
)