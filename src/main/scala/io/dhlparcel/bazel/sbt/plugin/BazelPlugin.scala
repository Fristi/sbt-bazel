package io.dhlparcel.bazel.sbt.plugin

import sbt.Keys._
import sbt.{Def, _}
import scala.util.Try

object BazelPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val bazelModules =
      taskKey[Unit]("Bazel-ify modules, introspects project settings and exports to BUILD per module. Be sure to run bazelComplete")

    val bazelComplete =
      taskKey[Unit]("Bazel-ify the project, first run `bazelModules`, this will output deps.txt and resolvers.txt per module. These can be deleted afterwards, but is needed to aggregated to DEPS.bzl file with de-duplicated external dependencies and resolvers")
  }

  import autoImport._

  private def quoted(str: String) = '"' + str + '"'

  private val aggregateFilter = ScopeFilter(
    inAggregates(ThisProject),
    inConfigurations(Compile)
  )


  private def scala_test(name: String, testDeps: String) =
    s"""scala_test(
      |  name = "$name-test",
      |  tags = ["test"],
      |  srcs = glob(["src/test/scala/**/*.scala"]),
      |  resources = glob(["src/test/resources/**/*.*"]),
      |  deps = [$testDeps]
      |)
      |""".stripMargin

  final case class Dep(groupId: String, artifact: String, version: String) {

    def yaml(modules: Set[Dep]) = {
      val sub = modules.map(m => s"""    ${m.artifact}:\n     lang: java\n     version: "${m.version}"\n\n""").mkString("\n")

      s"""   $groupId:\n$sub"""
    }
  }

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      bazelComplete := {
        val log = streams.value.log
        val dirs = baseDirectory.?.all(aggregateFilter).value.flatten
        val baseDir = baseDirectory.value
        val deps = dirs.flatMap { module => IO.read(module / "deps.txt").split("\r\n") }.toSet

        def parseDep(dep: String) ={
          dep.split(":").toList match {
            case groupId :: artifact :: version :: Nil =>
              Dep(groupId, artifact, version)
            case _ =>
              sys.error(s"parse error: '$dep''")
          }
        }

        val entries = deps.map(parseDep).groupBy(_.groupId).map { case (_, deps) => deps.head.yaml(deps) }.mkString("\n")
        val depsFile = s"dependencies:\n${entries}"

        IO.write(baseDir / "dependencies.yaml", depsFile)
      },
      bazelComplete / aggregate := false,
      bazelModules := {
        val log = streams.value.log
        val name = Keys.name.value.toLowerCase()
        val directory = baseDirectory.value
        val scalaBinaryVersionValue = scalaBinaryVersion.value
        val scalaVersionValue = scalaVersion.value
        val scalacOpts = scalacOptions.value.filterNot(_.startsWith("-P:wartremover")).map(quoted).mkString(",\r\n")
        val sbtCredentials = findCredentials.value
        val mainClz = (Compile / mainClass).value
        val intDeps = projectDependencies.value.map(moduleId => quoted(s"//${moduleId.name}"))
        val libraryDeps = libraryDependencies.value
          .map(moduleId => toDependency(moduleId, scalaVersionValue, scalaBinaryVersionValue))
          .toSet

        def getCredentials(url: URL): Option[BuildResolver.Credentials] =
          Try(Credentials.forHost(sbtCredentials, url.getHost)).toOption.flatten
            .map(c => BuildResolver.Credentials(c.userName, c.passwd))

        val resolvers = fullResolvers.value.collect {
          case repo: MavenRepository if !repo.root.startsWith("file:") =>
            val creds = getCredentials(new URL(repo.root))
            BuildResolver.BuildMavenRepository(repo.name, repo.root, creds)
          case repo: URLRepository =>
            val ivyPatterns = repo.patterns.ivyPatterns.mkString
            val creds = getCredentials(new URL(ivyPatterns))
            BuildResolver.BuildIvyRepository(repo.name, ivyPatterns, creds)
        }

        log.info(s"Generating BUILD in ${directory.name}")

        if(name == "root") {
          IO.write(directory / "BUILD", "")
        } else {

          val testLibs = libraryDeps.filter(_.isTest)
          val runtimeLibs = libraryDeps.filter(_.buildDef)
          val compilerPlugins = libraryDeps.filter(_.isPlugin).filterNot(_.groupId == "org.scalameta")
          val compilerDefs = compilerPlugins.map(_.asBazelMavenRelativeRef).map(quoted).mkString(",")
          val extDeps = runtimeLibs.map(x => quoted(x.asBazelMavenRelativeRef))

          val deps = (extDeps ++ intDeps).toList.sorted.mkString(",\r\n")

          val exportedDeps = (testLibs ++ runtimeLibs ++ compilerPlugins).map(_.asBazelMavenVersionedRef).toList.sorted.mkString("\r\n")
          val exportedResolvers = resolvers.map(_.show).mkString("\r\n")

          val sb = new StringBuilder()

          sb.append {
            mainClz match {
              case Some(clz) =>
                s"""scala_image(
                   |  name = "$name",
                   |  srcs = glob(["src/main/scala/**/*.scala"]),
                   |  resources = glob(["src/main/resources/**/*.*"]),
                   |  plugins = [$compilerDefs],
                   |  main_class = "$clz",
                   |  visibility = ["//visibility:public"],
                   |  deps = [$deps]
                   |)
                   |""".stripMargin
              case None =>
                s"""scala_library(
                   |  name = "$name",
                   |  srcs = glob(["src/main/scala/**/*.scala"]),
                   |  resources = glob(["src/main/resources/**/*.*"]),
                   |  plugins = [$compilerDefs],
                   |  visibility = ["//visibility:public"],
                   |  deps = [$deps]
                   |)
                   |""".stripMargin
            }
          }

          if(testLibs.nonEmpty) {
            sb.append(scala_test(name, (testLibs.map(x => quoted(x.asBazelMavenRelativeRef)) + quoted(s":$name")).toList.sorted.mkString(",\r\n")))
          }

          IO.write(directory / "BUILD", sb.toString())
          IO.write(directory / "deps.txt", exportedDeps)
          IO.write(directory / "resolvers.txt", exportedResolvers)
        }
      }
    )

  lazy val findCredentials: Def.Initialize[Task[Seq[Credentials]]] = Def.taskDyn {
    try {
      val allCredentials = TaskKey[Seq[Credentials]]("allCredentials").?
      Def.task {
        allCredentials.value.getOrElse(Nil)
      }
    } catch {
      case _: ClassNotFoundException => Def.task(credentials.value)
    }
  }

  private def crossName(
                         moduleId: ModuleID,
                         scalaVersion: String,
                         scalaBinaryVersion: String
                       ): Option[String] =
    CrossVersion(moduleId.crossVersion, scalaVersion, scalaBinaryVersion).map(_ (moduleId.name))

  private def toDependency(
                            moduleId: ModuleID,
                            scalaVersion: String,
                            scalaBinaryVersion: String,
                            configurations: Option[String] = None
                          ): BuildDependency =
    BuildDependency(
      groupId = moduleId.organization,
      artifactId = BuildArtifactId(moduleId.name, crossName(moduleId, scalaVersion, scalaBinaryVersion)),
      version = moduleId.revision,
      sbtVersion = moduleId.extraAttributes.get("e:sbtVersion"),
      scalaVersion = moduleId.extraAttributes.get("e:scalaVersion"),
      configurations = configurations.orElse(moduleId.configurations)
    )
}
