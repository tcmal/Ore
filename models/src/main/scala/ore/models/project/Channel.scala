package ore.models.project

import scala.language.higherKinds

import ore.data.Color
import ore.data.Color._
import ore.db.access.QueryView
import ore.db.impl.DefaultModelCompanion
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.common.Named
import ore.db.impl.schema.{ChannelTable, VersionTable}
import ore.db.{DbRef, Model, ModelQuery}
import ore.syntax._

import slick.lifted.TableQuery

/**
  * Represents a release channel for Project Versions. Each project gets it's
  * own set of channels.
  *
  * @param isNonReviewed Whether this channel should be excluded from the staff
  *                     approval queue
  * @param name        Name of channel
  * @param color       Color used to represent this Channel
  * @param projectId    ID of project this channel belongs to
  */
case class Channel(
    projectId: DbRef[Project],
    name: String,
    color: Color,
    isNonReviewed: Boolean = false
) extends Named {

  def isReviewed: Boolean = !isNonReviewed
}

object Channel extends DefaultModelCompanion[Channel, ChannelTable](TableQuery[ChannelTable]) {

  implicit val channelsAreOrdered: Ordering[Channel] = (x: Channel, y: Channel) => x.name.compare(y.name)

  implicit val query: ModelQuery[Channel] =
    ModelQuery.from(this)

  implicit val isProjectOwned: ProjectOwned[Channel] = (a: Channel) => a.projectId

  /**
    * The colors a Channel is allowed to have.
    */
  val Colors: Seq[Color] =
    Seq(Purple, Violet, Magenta, Blue, Aqua, Cyan, Green, DarkGreen, Chartreuse, Amber, Orange, Red)

  implicit class ChannelModelOps(private val self: Model[Channel]) extends AnyVal {

    /**
      * Returns all Versions in this channel.
      *
      * @return All versions
      */
    def versions[V[_, _]: QueryView](view: V[VersionTable, Model[Version]]): V[VersionTable, Model[Version]] =
      view.filterView(_.channelId === self.id.value)
  }
}
