import Dependencies._

ThisBuild / organization := "com.teamgehem"
ThisBuild / scalaVersion := "3.8.4"

lazy val `kyo_test` =
  project
    .in(file("."))
    .settings(name := "kyo_test")
    .settings(commonSettings)
    .settings(autoImportSettings)
    .settings(dependencies)

lazy val commonSettings = {
  lazy val commonScalacOptions =
    Seq(
      Compile / console / scalacOptions := {
        (Compile / console / scalacOptions)
          .value
          .filterNot(_.contains("wartremover"))
          .filterNot(Scalac.Lint.toSet)
          .filterNot(Scalac.FatalWarnings.toSet) :+ "-Wconf:any:silent"
      },
      Test / console / scalacOptions :=
        (Compile / console / scalacOptions).value,
    )

  lazy val otherCommonSettings =
    Seq(
      update / evictionWarningOptions := EvictionWarningOptions.empty
      // cs launch scalac:3.3.1 -- -Wconf:help
      // src is not yet available for Scala3
      // scalacOptions += s"-Wconf:src=${target.value}/.*:s",
    )

  Seq(
    commonScalacOptions,
    otherCommonSettings,
  ).reduceLeft(_ ++ _)
}

lazy val autoImportSettings =
  Seq(
    scalacOptions +=
      Seq(
        "java.lang",
        "scala",
        "scala.Predef",
        "scala.annotation",
        "scala.util.chaining",
      ).mkString(start = "-Yimports:", sep = ",", end = ""),
    Test / scalacOptions := {
      val existing = scalacOptions.value
        .filterNot(_.startsWith("-Yimports:"))
        .filterNot(_.contains("wartremover"))
      existing :+ Seq(
        "java.lang",
        "scala",
        "scala.Predef",
        "scala.annotation",
        "scala.util.chaining",
        "org.scalacheck",
        "org.scalacheck.Prop",
      ).mkString(start = "-Yimports:", sep = ",", end = "")
    },
  )

resolvers += "jitpack" at "https://jitpack.io"

lazy val dependencies =
  Seq(
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron" % "3.3.1",
      "io.getkyo" %% "kyo-core" % "1.0.0-RC4",
      "io.getkyo" %% "kyo-direct" % "1.0.0-RC4",
      "com.github.terdong.ironkyo" %% "ironkyo" % "0.1.2",
      "com.github.terdong.tokyo" %% "tokyo" % "0.1.2",
    ),
    libraryDependencies ++= Seq(
      com.eed3si9n.expecty.expecty,
      org.scalacheck.scalacheck,
      org.scalameta.`munit-scalacheck`,
      org.scalameta.munit,
      org.typelevel.`discipline-munit`,
      "com.github.terdong.tokyo" %% "tokyo-testkit" % "0.1.2",
    ).map(_ % Test),
  )
