package db.impl.query

import scala.language.higherKinds

import java.sql.Timestamp
import java.time.LocalDateTime

import play.api.mvc.RequestHeader

import controllers.sugar.Requests.ApiAuthInfo
import models.protocols.APIV2
import models.querymodels._
import ore.OreConfig
import ore.data.project.Category
import ore.db.DbRef
import ore.models.api.ApiKey
import ore.models.project.io.ProjectFiles
import ore.models.project.{ProjectSortingStrategy, TagColor}
import ore.models.user.User
import ore.permission.Permission

import cats.Reducible
import cats.data.NonEmptyList
import cats.syntax.all._
import cats.instances.list._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.Put
import io.circe.DecodingFailure
import zio.ZIO
import zio.blocking.Blocking

object APIV2Queries extends WebDoobieOreProtocol {

  implicit val apiV2TagRead: Read[List[APIV2QueryVersionTag]] =
    viewTagListRead.map(_.map(t => APIV2QueryVersionTag(t.name, t.data, t.color)))
  implicit val apiV2TagWrite: Write[List[APIV2QueryVersionTag]] =
    viewTagListWrite.contramap(_.map(t => ViewTag(t.name, t.data, t.color)))

  implicit val apiV2TagOptRead: Read[Option[List[APIV2QueryVersionTag]]] =
    Read[(Option[List[String]], Option[List[Option[String]]], Option[List[TagColor]])].map {
      case (Some(name), Some(data), Some(color)) =>
        Some(name.zip(data).zip(color).map { case ((n, d), c) => APIV2QueryVersionTag(n, d, c) })
      case _ => None
    }

  implicit val localDateTimeMeta: Meta[LocalDateTime] = Meta[Timestamp].timap(_.toLocalDateTime)(Timestamp.valueOf)

  def getApiAuthInfo(token: String): Query0[ApiAuthInfo] =
    sql"""|SELECT u.id,
          |       u.created_at,
          |       u.full_name,
          |       u.name,
          |       u.email,
          |       u.tagline,
          |       u.join_date,
          |       u.read_prompts,
          |       u.is_locked,
          |       u.language,
          |       ak.name,
          |       ak.owner_id,
          |       ak.token,
          |       ak.raw_key_permissions,
          |       aks.expires,
          |       CASE
          |           WHEN u.id IS NULL THEN 1::BIT(64)
          |           ELSE (coalesce(gt.permission, B'0'::BIT(64)) | 1::BIT(64) | (1::BIT(64) << 1) | (1::BIT(64) << 2)) &
          |                coalesce(ak.raw_key_permissions, (-1)::BIT(64))
          |           END
          |    FROM api_sessions aks
          |             LEFT JOIN api_keys ak ON aks.key_id = ak.id
          |             LEFT JOIN users u ON aks.user_id = u.id
          |             LEFT JOIN global_trust gt ON gt.user_id = u.id
          |  WHERE aks.token = $token""".stripMargin.query[ApiAuthInfo]

  def findApiKey(identifier: String, token: String): Query0[(DbRef[ApiKey], DbRef[User])] =
    sql"""SELECT k.id, k.owner_id FROM api_keys k WHERE k.token_identifier = $identifier AND k.token = crypt($token, k.token)"""
      .query[(DbRef[ApiKey], DbRef[User])]

  def createApiKey(
      name: String,
      ownerId: DbRef[User],
      tokenIdentifier: String,
      token: String,
      perms: Permission
  ): doobie.Update0 =
    sql"""|INSERT INTO api_keys (created_at, name, owner_id, token_identifier, token, raw_key_permissions)
          |VALUES (now(), $name, $ownerId, $tokenIdentifier, crypt($token, gen_salt('bf')), $perms)""".stripMargin.update

  def deleteApiKey(name: String, ownerId: DbRef[User]): doobie.Update0 =
    sql"""DELETE FROM api_keys k WHERE k.name = $name AND k.owner_id = $ownerId""".update

  //Like in, but takes a tuple
  def in2[F[_]: Reducible, A: Put, B: Put](f: Fragment, fs: F[(A, B)]): Fragment =
    fs.toList.map { case (a, b) => fr0"($a, $b)" }.foldSmash1(f ++ fr0"IN (", fr",", fr")")

  def projectSelectFrag(
      pluginId: Option[String],
      category: List[Category],
      tags: List[(String, Option[String])],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Fragment = {
    val userActionsTaken = currentUserId.fold(fr"FALSE, FALSE,") { id =>
      fr"""|EXISTS(SELECT * FROM project_stars s WHERE s.project_id = p.id AND s.user_id = $id)    AS user_stared,
           |EXISTS(SELECT * FROM project_watchers s WHERE s.project_id = p.id AND s.user_id = $id) AS user_watching,""".stripMargin
    }

    val base =
      sql"""|SELECT p.created_at,
            |       p.plugin_id,
            |       p.name,
            |       p.owner_name,
            |       p.slug,
            |       p.promoted_versions,
            |       p.views,
            |       p.downloads,
            |       p.stars,
            |       p.category,
            |       p.description,
            |       COALESCE(p.last_updated, p.created_at) AS last_updated,
            |       p.visibility,""".stripMargin ++ userActionsTaken ++
        fr"""|       ps.homepage,
             |       ps.issues,
             |       ps.source,
             |       ps.support,
             |       ps.license_name,
             |       ps.license_url,
             |       ps.forum_sync
             |  FROM home_projects p
             |         JOIN project_settings ps ON p.id = ps.project_id""".stripMargin

    val visibilityFrag =
      if (canSeeHidden) None
      else
        currentUserId.fold(Some(fr"(p.visibility = 1)")) { id =>
          Some(fr"(p.visibility = 1 OR (p.owner_id = $id AND p.visibility != 5))")
        }

    val (tagsWithData, tagsWithoutData) = tags.partitionEither {
      case (name, Some(data)) => Left((name, data))
      case (name, None)       => Right(name)
    }

    val filters = Fragments.whereAndOpt(
      pluginId.map(id => fr"p.plugin_id = $id"),
      NonEmptyList.fromList(category).map(Fragments.in(fr"p.category", _)),
      if (tags.nonEmpty) {
        val jsSelect =
          sql"""|SELECT pv.tag_name 
                |          FROM jsonb_to_recordset(p.promoted_versions) AS pv(tag_name TEXT, tag_version TEXT) """.stripMargin ++
            Fragments.whereAndOpt(
              NonEmptyList.fromList(tagsWithData).map(t => in2(fr"(pv.tag_name, pv.tag_version)", t)),
              NonEmptyList.fromList(tagsWithoutData).map(t => Fragments.in(fr"pv.tag_name", t))
            )

        Some(fr"EXISTS" ++ Fragments.parentheses(jsSelect))
      } else
        None,
      query.map(q => fr"p.search_words @@ websearch_to_tsquery($q)"),
      owner.map(o => fr"p.owner_name = $o"),
      visibilityFrag
    )

    base ++ filters
  }

  def projectQuery(
      pluginId: Option[String],
      category: List[Category],
      tags: List[(String, Option[String])],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      orderWithRelevance: Boolean,
      limit: Long,
      offset: Long
  )(
      implicit projectFiles: ProjectFiles[ZIO[Blocking, Nothing, ?]],
      requestHeader: RequestHeader,
      config: OreConfig
  ): Query0[ZIO[Blocking, Nothing, APIV2.Project]] = {
    val ordering = if (orderWithRelevance && query.nonEmpty) {
      val relevance = query.fold(fr"1") { q =>
        fr"ts_rank(p.search_words, websearch_to_tsquery($q)) DESC"
      }
      order match {
        case ProjectSortingStrategy.MostStars       => fr"p.stars *" ++ relevance
        case ProjectSortingStrategy.MostDownloads   => fr"p.downloads*" ++ relevance
        case ProjectSortingStrategy.MostViews       => fr"p.views *" ++ relevance
        case ProjectSortingStrategy.Newest          => fr"extract(EPOCH from p.created_at) *" ++ relevance
        case ProjectSortingStrategy.RecentlyUpdated => fr"extract(EPOCH from p.last_updated) *" ++ relevance
        case ProjectSortingStrategy.OnlyRelevance   => relevance
      }
    } else order.fragment

    val select = projectSelectFrag(pluginId, category, tags, query, owner, canSeeHidden, currentUserId)
    (select ++ fr"ORDER BY" ++ ordering ++ fr"LIMIT $limit OFFSET $offset").query[APIV2QueryProject].map(_.asProtocol)
  }

  def projectCountQuery(
      pluginId: Option[String],
      category: List[Category],
      tags: List[(String, Option[String])],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = {
    val select = projectSelectFrag(pluginId, category, tags, query, owner, canSeeHidden, currentUserId)
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(select) ++ fr"sq").query[Long]
  }

  def projectMembers(pluginId: String, limit: Long, offset: Long): Query0[APIV2.ProjectMember] =
    sql"""|SELECT u.name, array_agg(r.name)
          |  FROM projects p
          |         JOIN user_project_roles upr ON p.id = upr.project_id
          |         JOIN users u ON upr.user_id = u.id
          |         JOIN roles r ON upr.role_type = r.name
          |  WHERE p.plugin_id = $pluginId
          |  GROUP BY u.name ORDER BY max(r.permission::BIGINT) DESC LIMIT $limit OFFSET $offset""".stripMargin
      .query[APIV2QueryProjectMember]
      .map(_.asProtocol)

  def versionSelectFrag(
      pluginId: String,
      versionName: Option[String],
      tags: List[String]
  ): Fragment = {
    val base =
      sql"""|SELECT pv.created_at,
            |       pv.version_string,
            |       pv.dependencies,
            |       pv.visibility,
            |       pv.description,
            |       pv.downloads,
            |       pv.file_size,
            |       pv.hash,
            |       pv.file_name,
            |       u.name,
            |       pv.review_state,
            |       array_append(array_agg(pvt.name) FILTER ( WHERE pvt.name IS NOT NULL ), 'Channel')  AS tag_names,
            |       array_append(array_agg(pvt.data) FILTER ( WHERE pvt.name IS NOT NULL ), pc.name)    AS tag_datas,
            |       array_append(array_agg(pvt.color) FILTER ( WHERE pvt.name IS NOT NULL ), pc.color + 9) AS tag_colors
            |    FROM projects p
            |             JOIN project_versions pv ON p.id = pv.project_id
            |             LEFT JOIN users u ON pv.author_id = u.id
            |             LEFT JOIN project_version_tags pvt ON pv.id = pvt.version_id
            |             LEFT JOIN project_channels pc ON pv.channel_id = pc.id """.stripMargin

    val filters = Fragments.whereAndOpt(
      Some(fr"p.plugin_id = $pluginId"),
      versionName.map(v => fr"pv.version_string = $v"),
      NonEmptyList
        .fromList(tags)
        .map { t =>
          Fragments.or(
            Fragments.in(fr"pvt.name || ':' || pvt.data", t),
            Fragments.in(fr"pvt.name", t),
            Fragments.in(fr"'Channel:' || pc.name", t),
            Fragments.in(fr"'Channel'", t)
          )
        }
    )

    base ++ filters ++ fr"GROUP BY pv.id, u.id, pc.id"
  }

  def versionQuery(
      pluginId: String,
      versionName: Option[String],
      tags: List[String],
      limit: Long,
      offset: Long
  ): Query0[APIV2.Version] =
    (versionSelectFrag(pluginId, versionName, tags) ++ fr"ORDER BY pv.created_at DESC LIMIT $limit OFFSET $offset")
      .query[APIV2QueryVersion]
      .map(_.asProtocol)

  def versionCountQuery(pluginId: String, tags: List[String]): Query0[Long] =
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(versionSelectFrag(pluginId, None, tags)) ++ fr"sq").query[Long]

  def userQuery(name: String): Query0[APIV2.User] =
    sql"""|SELECT u.created_at, u.name, u.tagline, u.join_date, array_agg(r.name)
          |  FROM users u
          |         JOIN user_global_roles ugr ON u.id = ugr.user_id
          |         JOIN roles r ON ugr.role_id = r.id
          |  WHERE u.name = $name
          |  GROUP BY u.id""".stripMargin.query[APIV2QueryUser].map(_.asProtocol)

  private def actionFrag(
      table: Fragment,
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Fragment = {
    val base =
      sql"""|SELECT p.plugin_id,
            |       p.name,
            |       p.owner_name,
            |       p.slug,
            |       p.promoted_versions,
            |       p.views,
            |       p.downloads,
            |       p.stars,
            |       p.category,
            |       p.visibility
            |    FROM users u JOIN """.stripMargin ++ table ++
        fr"""|ps ON u.id = ps.user_id
             |             JOIN home_projects p ON ps.project_id = p.id""".stripMargin

    val visibilityFrag =
      if (canSeeHidden) None
      else
        currentUserId.fold(Some(fr"(p.visibility = 1 OR p.visibility = 2)")) { id =>
          Some(fr"(p.visibility = 1 OR p.visibility = 2 OR (p.owner_id = $id AND p.visibility != 5))")
        }

    val filters = Fragments.whereAndOpt(
      Some(fr"u.name = $user"),
      visibilityFrag
    )

    base ++ filters
  }

  private def actionQuery(
      table: Fragment,
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      limit: Long,
      offset: Long
  ): Query0[Either[DecodingFailure, APIV2.CompactProject]] = {
    val ordering = order.fragment

    val select = actionFrag(table, user, canSeeHidden, currentUserId)
    (select ++ fr"ORDER BY" ++ ordering ++ fr"LIMIT $limit OFFSET $offset")
      .query[APIV2QueryCompactProject]
      .map(_.asProtocol)
  }

  private def actionCountQuery(
      table: Fragment,
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = {
    val select = actionFrag(table, user, canSeeHidden, currentUserId)
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(select) ++ fr"sq").query[Long]
  }

  def starredQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      limit: Long,
      offset: Long
  ): Query0[Either[DecodingFailure, APIV2.CompactProject]] =
    actionQuery(Fragment.const("project_stars"), user, canSeeHidden, currentUserId, order, limit, offset)

  def starredCountQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = actionCountQuery(Fragment.const("project_stars"), user, canSeeHidden, currentUserId)

  def watchingQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      limit: Long,
      offset: Long
  ): Query0[Either[DecodingFailure, APIV2.CompactProject]] =
    actionQuery(Fragment.const("project_watchers"), user, canSeeHidden, currentUserId, order, limit, offset)

  def watchingCountQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = actionCountQuery(Fragment.const("project_watchers"), user, canSeeHidden, currentUserId)
}
