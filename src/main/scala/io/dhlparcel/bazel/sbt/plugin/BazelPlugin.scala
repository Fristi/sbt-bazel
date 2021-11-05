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

  type Analysis = sbt.internal.inc.Analysis

  def quoted(str: String) = '"' + str + '"'

  val aggregateFilter = ScopeFilter(
    inAggregates(ThisProject),
    inConfigurations(Compile)
  )

  // Evaluate "csrCacheDirectory" setting which is present only in sbt 1.3.0 or newer
  private lazy val csrCacheDirectoryValueTask = Def.task {
    val extracted: Extracted = Project.extract(state.value)
    val settings = extracted.session.original
    settings.find(_.key.key.label == "csrCacheDirectory") match {
      case Some(csrCacheDirectorySetting) =>
        val csrCacheDirectoryValue = csrCacheDirectorySetting.init.evaluate(extracted.structure.data).toString
        Some(csrCacheDirectoryValue)
      case _ => None
    }
  }

  private def toFile(x: AnyRef, csrCacheDirectoryValueOpt: Option[String], baseDirectoryValue: String): java.io.File = {
    if (x.getClass.getSimpleName.contains("VirtualFile")) {
      // sbt 1.4.0 or newer
      val id = x.getClass.getMethod("id").invoke(x).toString
      val path = id
        .replaceAllLiterally("${CSR_CACHE}", csrCacheDirectoryValueOpt.mkString)
        .replaceAllLiterally("${BASE}", baseDirectoryValue)
      new java.io.File(path)
    } else {
      // sbt 1.3.x or older
      x.asInstanceOf[java.io.File]
    }
  }

  def getAllLibraryDeps(analysis: Analysis, log: sbt.util.Logger)
                       (csrCacheDirectoryValueOpt: Option[String], baseDirectoryValue: String): Set[java.io.File] = {
    log.debug(
      s"Source to library relations:\n${analysis.relations.libraryDep.all.map(r => s"  ${r._1} -> ${r._2}").mkString("\n")}"
    )
    log.debug(s"Using CSR_CACHE=${csrCacheDirectoryValueOpt.mkString} BASE=$baseDirectoryValue")
    val allLibraryDeps = analysis.relations.allLibraryDeps.asInstanceOf[Set[AnyRef]]
      .map(x => toFile(x, csrCacheDirectoryValueOpt, baseDirectoryValue))
      .toSet
    log.debug(s"Library dependencies:\n${allLibraryDeps.mkString("  ", "\n  ", "")}")
    allLibraryDeps
  }

  private def getCompileDependencies(allLibraryDeps: Set[File], scalaVersion: ScalaVersion, log: Logger): Set[Dependency] = {
    val compileDependencyJarFiles =
      allLibraryDeps
        .filter(_.getName.endsWith(".jar"))
        .filterNot(_.getName == "rt.jar") // Java runtime


    val compileDependencies = compileDependencyJarFiles
      .flatMap(BoringStuff.jarFileToDependency(scalaVersion, log))

    log.debug(s"Compile depends on:\n${compileDependencies.mkString("  ", "\n  ", "")}")

    compileDependencies
  }

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      bazelComplete := {
        val log = streams.value.log
        val dirs = baseDirectory.?.all(aggregateFilter).value.flatten
        val baseDir = baseDirectory.value
        val deps = dirs.flatMap { module => IO.read(module / "deps.txt").split("\r\n") }.toSet.map(quoted)
        val resolvers = dirs.flatMap { module => IO.read(module / "resolvers.txt").split("\r\n") }.toSet.map(quoted)

        val depsFile = s"""DEPENDENCIES = [${deps.mkString(",\r\n")}]"""
        val resolversFile = s"""RESOLVERS = [${resolvers.mkString(",\r\n")}]"""

        IO.write(baseDir / "DEPS.bzl", depsFile)
        IO.write(baseDir / "RESOLVERS.bzl", resolversFile)
      },
      bazelComplete / aggregate := false,
      bazelModules := {
        val log = streams.value.log
        val directory = baseDirectory.value
        val scalaBinaryVersionValue = scalaBinaryVersion.value
        val scalaVersionValue = scalaVersion.value
        val scalacOpts = scalacOptions.value.map(quoted).mkString(",\r\n")
        val sbtCredentials = findCredentials.value
        val mainClz = (Compile / mainClass).value
        val name = Keys.name.value
        val csrCacheDirectoryValueOpt = csrCacheDirectoryValueTask.value
        val baseDirectoryValue = appConfiguration.value.baseDirectory().getCanonicalFile.toPath.toString
        val allLibraryDeps = getAllLibraryDeps(compile.in(Compile).value.asInstanceOf[Analysis], log)(csrCacheDirectoryValueOpt, baseDirectoryValue)
        val libDeps = getCompileDependencies(allLibraryDeps, ScalaVersion(scalaVersionValue, scalaBinaryVersionValue), log)
        val intDeps = projectDependencies.value.map(moduleId => s"//${quoted(moduleId.name)}")
        val extDeps = libDeps.map(x => quoted(x.asBazelMavenRelativeRef))

        val deps = (intDeps ++ extDeps).mkString(",\r\n")
        val exportedDeps = libDeps.map(_.asBazelMavenVersionedRef).mkString("\r\n")

        def getCredentials(url: URL): Option[Resolver.Credentials] =
          Try(Credentials.forHost(sbtCredentials, url.getHost)).toOption.flatten
            .map(c => Resolver.Credentials(c.userName, c.passwd))

        val resolvers = fullResolvers.value.collect {
          case repo: MavenRepository if !repo.root.startsWith("file:") =>
            val creds = getCredentials(new URL(repo.root))
            Resolver.MavenRepository(repo.name, repo.root, creds)
          case repo: URLRepository =>
            val ivyPatterns = repo.patterns.ivyPatterns.mkString
            val creds = getCredentials(new URL(ivyPatterns))
            Resolver.IvyRepository(repo.name, ivyPatterns, creds)
        }

        val exportedResolvers = resolvers.map(_.show).mkString("\r\n")

        val build = mainClz match {
          case Some(clz) =>
            log.info(s"Writing bazel: $name [ main class: $clz, scala_version: $scalaVersionValue]")
            s"""load("@io_bazel_rules_docker//scala:image.bzl", "scala_image")
               |
               |scala_image(
               |  name = "$name",
               |  srcs = glob(["src/main/scala/**/*.scala"]),
               |  resources = glob(["src/main/resources/**/*.*"]),
               |  scalacopts = [$scalacOpts],
               |  main_class = "$clz",
               |  visibility = ["//visibility:public"],
               |  deps = [$deps]
               |)""".stripMargin
          case None =>
            log.info(s"Writing bazel: $name [ scala_version: $scalaVersionValue]")
            s"""scala_library(
               |  name = "$name",
               |  srcs = glob(["src/main/scala/**/*.scala"]),
               |  resources = glob(["src/main/resources/**/*.*"]),
               |  scalacopts = [$scalacOpts],
               |  visibility = ["//visibility:public"],
               |  deps = [$deps]
               |)""".stripMargin
        }

        IO.write(directory / "BUILD", build)
        IO.write(directory / "deps.txt", exportedDeps)
        IO.write(directory / "resolvers.txt", exportedResolvers)
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
                          ): Dependency =
    Dependency(
      groupId = moduleId.organization,
      artifactId = ArtifactId(moduleId.name, crossName(moduleId, scalaVersion, scalaBinaryVersion)),
      version = moduleId.revision,
      sbtVersion = moduleId.extraAttributes.get("e:sbtVersion"),
      scalaVersion = moduleId.extraAttributes.get("e:scalaVersion"),
      configurations = configurations.orElse(moduleId.configurations)
    )
}
