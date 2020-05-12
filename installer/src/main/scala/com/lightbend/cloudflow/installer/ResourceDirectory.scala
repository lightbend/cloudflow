package cloudflow.installer

object ResourceDirectory {
  val path     = os.pwd / "yaml" / "kustomize"
  def exists() = os.isDir(path)
}
