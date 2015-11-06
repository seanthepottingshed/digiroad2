package fi.liikennevirasto.digiroad2.pointasset.oracle

import fi.liikennevirasto.digiroad2.{Point, PointAsset}
import fi.liikennevirasto.digiroad2.asset.oracle.Queries._
import fi.liikennevirasto.digiroad2.oracle.MassQuery
import slick.jdbc.{PositionedResult, GetResult}
import slick.jdbc.StaticQuery.interpolation
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession

trait OraclePointAssetDao {
  def getByMmldIds(mmlIds: Seq[Long]): Seq[PointAsset] = {
    MassQuery.withIds(mmlIds.toSet) { idTableName =>
      sql"""
        select a.id, pos.mml_id, a.geometry, pos.start_measure
        from asset a
        join asset_link al on a.id = al.asset_id
        join lrm_position pos on al.position_id = pos.id
        join  #$idTableName i on i.id = pos.mml_id
        where a.asset_type_id = 200 and floating = 0
       """.as[PointAsset].list
    }
  }

  implicit val getPointAsset = new GetResult[PointAsset] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val mmlId = r.nextLong()
      val point = r.nextBytesOption().map(bytesToPoint).get
      val mValue = r.nextDouble()
      PointAsset(id, mmlId, point.x, point.y, mValue)
    }
  }
}

object OraclePointAssetDao extends OraclePointAssetDao
