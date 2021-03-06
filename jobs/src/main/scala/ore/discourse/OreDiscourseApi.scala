package ore.discourse

import ore.db.Model
import ore.models.project.{Project, Version}

import cats.tagless.autoFunctorK

/**
  * Manages forum threads and posts for Ore models.
  */
@autoFunctorK
trait OreDiscourseApi[+F[_]] {

  /**
    * Creates a new topic for the specified [[Project]].
    *
    * @param project Project to create topic for.
    * @return        True if successful
    */
  def createProjectTopic(project: Model[Project]): F[Either[DiscourseError, Model[Project]]]

  /**
    * Updates a [[Project]]'s forum topic with the appropriate content.
    *
    * @param project  Project to update topic for
    * @return         True if successful
    */
  def updateProjectTopic(project: Model[Project]): F[Either[DiscourseError, Unit]]

  /**
    * Posts a new reply to a [[Project]]'s forum topic.
    *
    * @param topicId  Topic to post to
    * @param poster   User who is posting
    * @param content  Post content
    * @return         Error Discourse returns
    */
  def postDiscussionReply(topicId: Int, poster: String, content: String): F[Either[DiscourseError, DiscoursePost]]

  /**
    * Posts a new "Version release" to a [[Project]]'s forum topic.
    *
    * @param project Project to post release to
    * @param version Version of project
    * @return
    */
  def createVersionPost(project: Model[Project], version: Model[Version]): F[Either[DiscourseError, Model[Version]]]

  /**
    * Updates a [[Version]]s forum post with the appropriate content.
    * @param project The owner of the version
    * @param version The version to update post for
    * @return True if successful
    */
  def updateVersionPost(project: Model[Project], version: Model[Version]): F[Either[DiscourseError, Unit]]

  /**
    * Delete a forum topic.
    *
    * @param topicId Topic to delete
    * @return         True if deleted
    */
  def deleteTopic(topicId: Int): F[Either[DiscourseError, Unit]]
}
