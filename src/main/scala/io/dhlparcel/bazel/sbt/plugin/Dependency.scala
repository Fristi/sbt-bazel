package io.dhlparcel.bazel.sbt.plugin

final case class ArtifactId(
                                     name: String,
                                     maybeCrossName: Option[String]
                                   ) {
  def normalizedName = maybeCrossName.getOrElse(name)
}
final case class Dependency(
                                     groupId: String,
                                     artifactId: ArtifactId,
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

  def asBazelMavenRelativeRef: String =
    "@maven//:" + bazelStr(groupId) + "_" + bazelArtifactId(bazelStr)
}

sealed trait Resolver extends Product with Serializable {
  def show: String
}

object Resolver {
  final case class Credentials(user: String, pass: String) {
    def show = s"$user:$pass"
  }

  final case class MavenRepository(
                                    name: String,
                                    location: String,
                                    credentials: Option[Credentials]
                                  ) extends Resolver {
    override def show: String = location
  }

  final case class IvyRepository(name: String, pattern: String, credentials: Option[Credentials])
    extends Resolver {
    override def show: String = pattern
  }
}
