package util

import java.nio.file.{Files, Path}

import models.author.Author
import play.api.Play
import play.api.libs.Files.TemporaryFile
import play.api.Play.current

import scala.util.Try

object PluginManager {

  val UPLOADS_DIR = Play.application.path.toPath.resolve("uploads")
  val PLUGIN_DIR = UPLOADS_DIR.resolve("plugins")
  val TEMP_DIR = UPLOADS_DIR.resolve("tmp")

  /**
    * Initializes a new PluginFile with the specified owner and temporary file.
    *
    * @param tmp Temporary file
    * @param owner Project owner
    * @return New plugin file
    */
  def initUpload(tmp: TemporaryFile, owner: Author): Try[PluginFile] = Try {
    val tmpPath = TEMP_DIR.resolve(owner.name).resolve("plugin.jar")
    val plugin = new PluginFile(tmpPath, owner)
    if (!Files.exists(tmpPath.getParent)) {
      Files.createDirectories(tmpPath.getParent)
    }
    tmp.moveTo(plugin.getPath.toFile, replace = true)
    plugin
  }

  def uploadPlugin(plugin: PluginFile): Try[Unit] = Try {
    plugin.getMeta match {
      case None => throw new IllegalArgumentException("Specified PluginFile has no meta loaded.")
      case Some(meta) =>
        val channel = "alpha" // TODO: Determine release channel from version string
        val oldPath = plugin.getPath
        val newPath = getUploadPath(plugin.getOwner.name, meta.getName, meta.getVersion, channel)
        if (!Files.exists(newPath.getParent)) {
          Files.createDirectories(newPath.getParent)
        }
        Files.move(oldPath, newPath)
        Files.delete(oldPath.getParent)
    }
  }

  /**
    * Returns the Path to where the specified Version should be.
    *
    * @param owner Project owner
    * @param name Project name
    * @param version Project version
    * @param channel Project channel
    * @return Path to supposed file
    */
  def getUploadPath(owner: String, name: String, version: String, channel: String): Path = {
    PLUGIN_DIR.resolve(owner).resolve(name).resolve("%s-%s-%s.jar".format(name, version, channel.toLowerCase))
  }

}
