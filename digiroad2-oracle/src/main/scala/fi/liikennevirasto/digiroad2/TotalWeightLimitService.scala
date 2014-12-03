package fi.liikennevirasto.digiroad2

import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import fi.liikennevirasto.digiroad2.LinkChain.GeometryDirection.{AgainstLinkChain, TowardsLinkChain}
import fi.liikennevirasto.digiroad2.asset.oracle.Queries.bonecpToInternalConnection
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, RoadLinkType}
import fi.liikennevirasto.digiroad2.linearasset.LinearAsset
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.oracle.collections.OracleArray
import scala.collection.JavaConversions._
import scala.slick.driver.JdbcDriver.backend.Database
import scala.slick.driver.JdbcDriver.backend.Database.dynamicSession
import scala.slick.jdbc.StaticQuery.interpolation
import scala.slick.jdbc.{StaticQuery => Q}
import org.joda.time.DateTime
import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.asset.oracle.AssetPropertyConfiguration.{DateTimePropertyFormat => DateTimeFormat}
import fi.liikennevirasto.digiroad2.asset.oracle.Queries

case class TotalWeightLimitLink(id: Long, roadLinkId: Long, sideCode: Int, value: Int, points: Seq[Point], position: Option[Int] = None, towardsLinkChain: Option[Boolean] = None, expired: Boolean = false)
case class TotalWeightLimit(id: Long, limit: Int, expired: Boolean, endpoints: Set[Point],
                            modifiedBy: Option[String], modifiedDateTime: Option[String],
                            createdBy: Option[String], createdDateTime: Option[String])

trait TotalWeightLimitOperations {
  def withDynTransaction[T](f: => T): T

  lazy val dataSource = {
    val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/bonecp.properties"))
    new BoneCPDataSource(cfg)
  }

  private def getLinksWithPositions(links: Seq[TotalWeightLimitLink]): Seq[TotalWeightLimitLink] = {
    def getLinkEndpoints(link: TotalWeightLimitLink): (Point, Point) = GeometryUtils.geometryEndpoints(link.points)
    val linkChain = LinkChain(links, getLinkEndpoints)
    linkChain.map { chainedLink =>
      val rawLink = chainedLink.rawLink
      val towardsLinkChain = chainedLink.geometryDirection match {
        case TowardsLinkChain => true
        case AgainstLinkChain => false
      }
      rawLink.copy(position = Some(chainedLink.linkPosition), towardsLinkChain = Some(towardsLinkChain))
    }
  }

  private def totalWeightLimitLinksById(id: Long): Seq[(Long, Long, Int, Int, Seq[Point], Option[String], Option[DateTime], Option[String], Option[DateTime], Boolean)] = {
    val totalWeightLimits = sql"""
      select a.id, pos.road_link_id, pos.side_code, s.value as total_weight_limit, pos.start_measure, pos.end_measure,
             a.modified_by, a.modified_date, a.created_by, a.created_date, case when a.valid_to <= sysdate then 1 else 0 end as expired
      from asset a
      join asset_link al on a.id = al.asset_id
      join lrm_position pos on al.position_id = pos.id
      join property p on a.asset_type_id = p.asset_type_id and p.public_id = 'kokonaispainorajoitus'
      join number_property_value s on s.asset_id = a.id and s.property_id = p.id
      where a.asset_type_id = 30 and a.id = $id
      """.as[(Long, Long, Int, Int, Double, Double, Option[String], Option[DateTime], Option[String], Option[DateTime], Boolean)].list

    totalWeightLimits.map { case (segmentId, roadLinkId, sideCode, value, startMeasure, endMeasure, modifiedBy, modifiedAt, createdBy, createdAt, expired) =>
      val points = RoadLinkService.getRoadLinkGeometry(roadLinkId, startMeasure, endMeasure)
      (segmentId, roadLinkId, sideCode, value, points, modifiedBy, modifiedAt, createdBy, createdAt, expired)
    }
  }

  def getByBoundingBox(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Seq[TotalWeightLimitLink] = {
    withDynTransaction {
      val roadLinks = RoadLinkService.getRoadLinks(bounds, false, municipalities)
      val roadLinkIds = roadLinks.map(_._1).toList

      val totalWeightLimits = OracleArray.fetchTotalWeightLimitsByRoadLinkIds(roadLinkIds, bonecpToInternalConnection(dynamicSession.conn))

      val linkGeometries: Map[Long, (Seq[Point], Double, RoadLinkType, Int)] =
      roadLinks.foldLeft(Map.empty[Long, (Seq[Point], Double, RoadLinkType, Int)]) { (acc, roadLink) =>
          acc + (roadLink._1 -> (roadLink._2, roadLink._3, roadLink._4, roadLink._5))
        }

      val totalWeightLimitsWithGeometry: Seq[TotalWeightLimitLink] = totalWeightLimits.map { link =>
        val (assetId, roadLinkId, sideCode, speedLimit, startMeasure, endMeasure) = link
        val geometry = GeometryUtils.truncateGeometry(linkGeometries(roadLinkId)._1, startMeasure, endMeasure)
        TotalWeightLimitLink(assetId, roadLinkId, sideCode, speedLimit, geometry)
      }

      totalWeightLimitsWithGeometry.groupBy(_.id).mapValues(getLinksWithPositions).values.flatten.toSeq
    }
  }

  def getById(id: Long): Option[TotalWeightLimit] = {
    withDynTransaction {
      val links = totalWeightLimitLinksById(id)
      if (links.isEmpty) None
      else {
        val linkEndpoints: List[(Point, Point)] = links.map { link => GeometryUtils.geometryEndpoints(link._5) }.toList
        val limitEndpoints = LinearAsset.calculateEndPoints(linkEndpoints)
        val head = links.head
        val (_, _, _, limit, _, modifiedBy, modifiedAt, createdBy, createdAt, expired) = head
        Some(TotalWeightLimit(id, limit, expired, limitEndpoints, modifiedBy, modifiedAt.map(DateTimeFormat.print), createdBy, createdAt.map(DateTimeFormat.print)))
      }
    }
  }

  private def updateNumberProperty(assetId: Long, propertyId: Long, value: Int): Int =
    sqlu"update number_property_value set value = $value where asset_id = $assetId and property_id = $propertyId".first

  private def updateTotalWeightLimitValue(id: Long, value: Int, username: String): Option[Long] = {
    val propertyId = Q.query[String, Long](Queries.propertyIdByPublicId).firstOption("kokonaispainorajoitus").get
    val assetsUpdated = Queries.updateAssetModified(id, username).first
    val propertiesUpdated = updateNumberProperty(id, propertyId, value)
    if (assetsUpdated == 1 && propertiesUpdated == 1) {
      Some(id)
    } else {
      None
    }
  }

  private def updateTotalWeightLimitExpiration(id: Long, expired: Boolean, username: String) = {
    val assetsUpdated = Queries.updateAssetModified(id, username).first
    val propertiesUpdated = expired match {
      case true => sqlu"update asset set valid_to = sysdate where id = $id".first
      case false => sqlu"update asset set valid_to = null where id = $id".first
    }
    if (assetsUpdated == 1 && propertiesUpdated == 1) {
      Some(id)
    } else {
      None
    }
  }

  def updateTotalWeightLimit(id: Long, value: Option[Int], expired: Boolean, username: String): Option[Long] = {
    withDynTransaction {
      val valueUpdate: Option[Long] = value.flatMap(updateTotalWeightLimitValue(id, _, username))
      val expirationUpdate: Option[Long] = updateTotalWeightLimitExpiration(id, expired, username)
      val updatedId = valueUpdate.orElse(expirationUpdate)
      if (updatedId.isEmpty) dynamicSession.rollback()
      updatedId
    }
  }
}

object TotalWeightLimitService extends TotalWeightLimitOperations {
  def withDynTransaction[T](f: => T): T = Database.forDataSource(dataSource).withDynTransaction(f)
}
