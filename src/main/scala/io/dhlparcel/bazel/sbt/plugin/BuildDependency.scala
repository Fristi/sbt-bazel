package io.dhlparcel.bazel.sbt.plugin

import coursier._
import coursier.core.Authentication
import coursier.ivy._

final case class BuildArtifactId(
                                     name: String,
                                     maybeCrossName: Option[String]
                                   ) {
  def normalizedName = maybeCrossName.getOrElse(name)
}
final case class BuildDependency(
                                  groupId: String,
                                  artifactId: BuildArtifactId,
                                  version: String,
                                  sbtVersion: Option[String],
                                  scalaVersion: Option[String],
                                  configurations: Option[String]
                                   ) {

  def bazelArtifactId(transform: String => String) = scalaVersion match {
    case Some(v) => transform(artifactId.normalizedName) + "_" + transform(v)
    case None => transform(artifactId.normalizedName)
  }

  def asBazelMavenVersionedRef: String =
    groupId + ":" + bazelArtifactId(identity) + ":" + version

  private def bazelStr(str: String) =
    str
      .replace(".", "_")
      .replace("-", "_")

  def isTest =
    configurations.exists(_.contains("test"))

  def isIntegrationTest =
    configurations.exists(_.contains("it"))

  def isPlugin =
    configurations.exists(_.startsWith("plugin"))

  def buildDef =
    configurations.isEmpty

  def coursier: Dependency =
    Dependency(Module(Organization(groupId), ModuleName(artifactId.normalizedName)), version)

  def asBazelMavenRelativeRef: String =
    "@maven//:" + bazelStr(groupId) + "_" + bazelArtifactId(bazelStr)
}

sealed trait BuildResolver extends Product with Serializable {
  def show: String
  def coursier: Repository
}

object BuildResolver {
  final case class Credentials(user: String, pass: String) {
    def show = s"$user:$pass"
  }

  final case class BuildMavenRepository(
                                    name: String,
                                    location: String,
                                    credentials: Option[Credentials]
                                  ) extends BuildResolver {
    override def show: String = location

    def coursier: Repository = MavenRepository(location, credentials.map(c => Authentication(c.user, c.pass)))
  }

  final case class BuildIvyRepository(name: String, pattern: String, credentials: Option[Credentials])
    extends BuildResolver {
    override def show: String = pattern

    def coursier: Repository = ??? //IvyRepository.fromPattern(Pattern(pattern))
  }
}
