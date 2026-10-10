val commonSettings = Seq(
    scalaVersion := "3.7.2",
    scalacOptions ++= List("-deprecation", "-feature", "-Xfatal-warnings")
)

/// Dependencies

val webappLibRepo = uri("https://gitlab.epfl.ch/cs214/ul2024/webapp-lib.git#v0.27.0")

lazy val client: ProjectReference = ProjectRef(webappLibRepo, "webappLibJS")
lazy val server: ProjectReference = ProjectRef(webappLibRepo, "webappLibJVM")

/// Commands

lazy val copyJsTask = TaskKey[Unit]("copyJsTask", "Copy javascript files to server target directory")
lazy val packageForDeployment = TaskKey[Unit]("packageForDeployment", "Package the project into a single über-jar")

/// CopyJSTask factory

/** Copies the generated main.js file to the server's static files directory.
 * @param fastOptimized If set to true, will copy the fastOptimized version of the main.js file. If set to false, will copy the fully optimized version.
 */
def copyJsTaskFactory(fastOptimized: Boolean) = Def.task {
  println("[info] Copying" + (if (!fastOptimized) { " optimized" } else { "" }) + " generated main.js to server's static files directory...")
  val inDir = baseDirectory.value / ("apps/js/target/scala-3.7.2/" + (if (fastOptimized) { "app-fastopt" } else { "app-opt" }) + "/")
  val outDir = baseDirectory.value / "apps/jvm/src/main/resources/www/static/"
  Seq("main.js", "main.js.map") map { p => (inDir / p, outDir / p) } foreach { f => IO.copyFile(f._1, f._2) }
}


/// Aggregate project

lazy val app = (crossProject(JVMPlatform, JSPlatform) in file("./apps"))
  .settings(
    commonSettings
  ).settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "ujson" % "4.3.0",
    )
  ).jsSettings(
    scalaJSUseMainModuleInitializer := true,
    test / aggregate := false,
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (_.withClosureCompiler(false)),
    Test / test := {},
    Test / testOnly := {},
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-library" % "3.7.2",
      "org.scala-js" %%% "scalajs-dom" % "2.8.1",
      "com.lihaoyi" %%% "scalatags" % "0.13.1",
    )
  ).jvmSettings(
    assembly / assemblyJarName := "webapp.jar",
    assembly / assemblyMergeStrategy := {
      case _ => sbtassembly.MergeStrategy.first
    },
    assembly / mainClass := Some("apps.MainJVM"),
    run / fork := true,
    Global / cancelable := true,
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "toolkit-test" % "0.7.0" % Test,
    )
  )

lazy val appJS = app.js.dependsOn(client)
lazy val appJVM = app.jvm.dependsOn(server % "compile->compile;test->test")

/// Aggregate project

lazy val webappApp = (project in file("."))
  .aggregate(
    appJS, appJVM,
  ).settings(
    name := "webappApp",
    commonSettings
  ).settings(
    copyJsTask := copyJsTaskFactory(true).value,
    Test / test := Def.sequential(
      (Test / testOnly).toTask("")
    ).value,
    packageForDeployment := Def.sequential(
      Compile / compile,
      (appJS / Compile / fullLinkJS).toTask,
      copyJsTaskFactory(false),
      (appJVM / assembly).toTask
    ).value,
    Compile / run := Def.sequential(
      Compile / compile,
      (appJS / Compile / fastLinkJS).toTask,
      copyJsTask,
      (appJVM / Compile / run).toTask(""),
    ).value,
  )
