ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "example"

lazy val root = (project in file("."))
  .settings(
    name := "roman-gen"
  )