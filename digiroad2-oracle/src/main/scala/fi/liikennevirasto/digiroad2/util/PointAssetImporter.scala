package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.ConversionDatabase._
import fi.liikennevirasto.digiroad2.asset.oracle.Queries.updateAssetGeometry
import fi.liikennevirasto.digiroad2.asset.oracle.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.pointasset.oracle.PedestrianCrossing
import fi.liikennevirasto.digiroad2.{PersistedPointAsset, Point, PointAssetOperations, VVHClient}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation


object PointAssetImporter {

  case class ImportedPointAsset(id: Long,
                                mmlId: Long,
                                mValue: Double,
                                floating: Boolean,
                                lon: Double,
                                lat: Double,
                                municipalityCode: Int) extends PersistedPointAsset

  def humanReadableDurationSince(startTime: DateTime) = AssetDataImporter.humanReadableDurationSince(startTime)

  def importPedestrianCrossings(database: DatabaseDef, vvhServiceHost: String): Unit = {
    val query = sql"""
         select s.tielinkki_id, t.mml_id, t.kunta_nro, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)), s.alkum, s.loppum
           from segments s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
           where s.tyyppi = 17
        """

    val pedestrianCrossings = database.withDynSession {
      query.as[(Long, Long, Int, Seq[Point], Double, Double)].list
    }

    val roadLinks = new VVHClient(vvhServiceHost).fetchVVHRoadlinks(pedestrianCrossings.map(_._2).toSet)
    val groupSize = 3000
    val groupedCrossings = pedestrianCrossings.grouped(groupSize).toList
    val totalGroupCount = groupedCrossings.length

    OracleDatabase.withDynTransaction {
      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, MUNICIPALITY_CODE, FLOATING, CREATED_DATE, CREATED_BY) values (?, ?, ?, ?, SYSDATE, 'dr1_conversion')")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, ROAD_LINK_ID, MML_ID, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?, ?)")
      val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")

      println(s"*** Importing ${pedestrianCrossings.length} pedestrian crossings in $totalGroupCount groups of $groupSize each")

      groupedCrossings.zipWithIndex.foreach { case (crossings, i) =>
        val startTime = DateTime.now()

        val assetGeometries = crossings.map { case (roadLinkId, mmlId, municipalityCode, points, startMeasure, endMeasure) =>
          val assetId = Sequences.nextPrimaryKeySeqValue
          assetPS.setLong(1, assetId)
          assetPS.setInt(2, 200)
          assetPS.setInt(3, municipalityCode)
          val pointAsset = PedestrianCrossing(assetId, mmlId, points.head.x, points.head.y, startMeasure, false, municipalityCode)
          assetPS.setBoolean(4, PointAssetOperations.isFloating(
            pointAsset,
            roadLinks.find(_.mmlId == mmlId).map { x => (x.municipalityCode, x.geometry) }
          ))
          assetPS.addBatch()

          val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
          lrmPositionPS.setLong(1, lrmPositionId)
          lrmPositionPS.setLong(2, roadLinkId)
          lrmPositionPS.setLong(3, mmlId)
          lrmPositionPS.setDouble(4, startMeasure)
          lrmPositionPS.setDouble(5, endMeasure)
          lrmPositionPS.setInt(6, 1)
          lrmPositionPS.addBatch()

          assetLinkPS.setLong(1, assetId)
          assetLinkPS.setLong(2, lrmPositionId)
          assetLinkPS.addBatch()

          (assetId, points.head)
        }

        assetPS.executeBatch()
        lrmPositionPS.executeBatch()
        assetLinkPS.executeBatch()

        assetGeometries.foreach { case (assetId, point) => updateAssetGeometry(assetId, point) }

        println(s"*** Imported ${crossings.length} pedestrian crossings in ${humanReadableDurationSince(startTime)} (done ${i + 1}/$totalGroupCount)" )
      }
      assetPS.close()
      lrmPositionPS.close()
      assetLinkPS.close()
    }
  }

  def importObstacles(database: DatabaseDef, vvhServiceHost: String, conversionTypeId: Int, enumeratedValue: Int): Unit = {
    val query = sql"""
         select s.tielinkki_id, t.mml_id, t.kunta_nro, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)), s.alkum, s.loppum
           from segments s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
           where s.tyyppi = $conversionTypeId
        """

    val obstacles = database.withDynSession {
      query.as[(Long, Long, Int, Seq[Point], Double, Double)].list
    }

    val roadLinks = new VVHClient(vvhServiceHost).fetchVVHRoadlinks(obstacles.map(_._2).toSet)
    val groupSize = 3000
    val groupedObstacles = obstacles.grouped(groupSize).toList
    val totalGroupCount = groupedObstacles.length

    OracleDatabase.withDynTransaction {
      val propertyId = sql"""select id from property where public_id = 'esterakennelma'""".as[Long].first
      val enumeratedValueId = sql"""select id from ENUMERATED_VALUE where PROPERTY_ID = $propertyId and value = $enumeratedValue""".as[Long].first

      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, MUNICIPALITY_CODE, FLOATING, CREATED_DATE, CREATED_BY) values (?, ?, ?, ?, SYSDATE, 'dr1_conversion')")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, ROAD_LINK_ID, MML_ID, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?, ?)")
      val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")
      val singleChoicePS = dynamicSession.prepareStatement(s"insert into single_choice_value (asset_id, enumerated_value_id, property_id) values (?, $enumeratedValueId, $propertyId)")

      println(s"*** Importing ${obstacles.length} obstacles in $totalGroupCount groups of $groupSize each")

      groupedObstacles.zipWithIndex.foreach { case (obstacles, i) =>
        val startTime = DateTime.now()

        val assetGeometries = obstacles.map { case (roadLinkId, mmlId, municipalityCode, points, startMeasure, endMeasure) =>
          val assetId = Sequences.nextPrimaryKeySeqValue
          assetPS.setLong(1, assetId)
          assetPS.setInt(2, 220)
          assetPS.setInt(3, municipalityCode)
          val pointAsset = ImportedPointAsset(assetId, mmlId, startMeasure, false, points.head.x, points.head.y, municipalityCode)
          assetPS.setBoolean(4, PointAssetOperations.isFloating(
            pointAsset,
            roadLinks.find(_.mmlId == mmlId).map { x => (x.municipalityCode, x.geometry) }
          ))
          assetPS.addBatch()

          val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
          lrmPositionPS.setLong(1, lrmPositionId)
          lrmPositionPS.setLong(2, roadLinkId)
          lrmPositionPS.setLong(3, mmlId)
          lrmPositionPS.setDouble(4, startMeasure)
          lrmPositionPS.setDouble(5, endMeasure)
          lrmPositionPS.setInt(6, 1)
          lrmPositionPS.addBatch()

          assetLinkPS.setLong(1, assetId)
          assetLinkPS.setLong(2, lrmPositionId)
          assetLinkPS.addBatch()

          singleChoicePS.setLong(1, assetId)
          singleChoicePS.addBatch()

          (assetId, points.head)
        }

        assetPS.executeBatch()
        lrmPositionPS.executeBatch()
        assetLinkPS.executeBatch()
        singleChoicePS.executeBatch()

        assetGeometries.foreach { case (assetId, point) => updateAssetGeometry(assetId, point) }

        println(s"*** Imported ${obstacles.length} obstacles in ${humanReadableDurationSince(startTime)} (done ${i + 1}/$totalGroupCount)" )
      }
      assetPS.close()
      lrmPositionPS.close()
      assetLinkPS.close()
      singleChoicePS.close()
    }
  }

  def importRailwayCrossings(database: DatabaseDef, vvhServiceHost: String): Unit = {
    val query = sql"""
         select s.tielinkki_id, t.mml_id, t.kunta_nro, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)),  s.alkum, s.loppum, s.varustus, s.nimi_s
           from segm_tasoristeys s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
        """

    val railwayCrossings = database.withDynSession {
      query.as[(Long, Long, Int, Seq[Point], Double, Double, Int, String)].list
    }

    val roadLinks = new VVHClient(vvhServiceHost).fetchVVHRoadlinks(railwayCrossings.map(_._2).toSet)
    val groupSize = 3000
    val groupedObstacles = railwayCrossings.grouped(groupSize).toList
    val totalGroupCount = groupedObstacles.length

    OracleDatabase.withDynTransaction {
      val safetyGearPropertyId = sql"""select id from property where public_id = 'turvavarustus'""".as[Long].first
      val safetyGearEnumValueIds = sql"""select value, id from ENUMERATED_VALUE where PROPERTY_ID = $safetyGearPropertyId""".as[(Int, Long)].list.groupBy(_._1).mapValues(_.head._2)
      val textPropertyId = sql"""select id from property where public_id = 'rautatien_tasoristeyksen_nimi'""".as[Long].first

      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, MUNICIPALITY_CODE, FLOATING, CREATED_DATE, CREATED_BY) values (?, ?, ?, ?, SYSDATE, 'dr1_conversion')")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, ROAD_LINK_ID, MML_ID, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?, ?)")
      val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")
      val singleChoicePS = dynamicSession.prepareStatement(s"insert into single_choice_value (asset_id, enumerated_value_id, property_id) values (?, ?, $safetyGearPropertyId)")
      val textPropertyPS =  dynamicSession.prepareStatement(s"insert into text_property_value (id, asset_id, property_id, value_fi) values (?, ?, $textPropertyId, ?)")


      println(s"*** Importing ${railwayCrossings.length} railway crossings in $totalGroupCount groups of $groupSize each")

      groupedObstacles.zipWithIndex.foreach { case (railwayCrossings, i) =>
        val startTime = DateTime.now()

        val assetGeometries = railwayCrossings.map { case (roadLinkId, mmlId, municipalityCode, points, startMeasure, endMeasure, safetyGear, name) =>
          val assetId = Sequences.nextPrimaryKeySeqValue
          assetPS.setLong(1, assetId)
          assetPS.setInt(2, 230)
          assetPS.setInt(3, municipalityCode)
          val pointAsset = ImportedPointAsset(assetId, mmlId, startMeasure, false, points.head.x, points.head.y, municipalityCode)
          assetPS.setBoolean(4, PointAssetOperations.isFloating(
            pointAsset,
            roadLinks.find(_.mmlId == mmlId).map { x => (x.municipalityCode, x.geometry) }
          ))
          assetPS.addBatch()

          val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
          lrmPositionPS.setLong(1, lrmPositionId)
          lrmPositionPS.setLong(2, roadLinkId)
          lrmPositionPS.setLong(3, mmlId)
          lrmPositionPS.setDouble(4, startMeasure)
          lrmPositionPS.setDouble(5, endMeasure)
          lrmPositionPS.setInt(6, 1)
          lrmPositionPS.addBatch()

          assetLinkPS.setLong(1, assetId)
          assetLinkPS.setLong(2, lrmPositionId)
          assetLinkPS.addBatch()

          singleChoicePS.setLong(1, assetId)
          singleChoicePS.setLong(2, safetyGearEnumValueIds(safetyGear))
          singleChoicePS.addBatch()

          val id = Sequences.nextPrimaryKeySeqValue
          textPropertyPS.setLong(1, id)
          textPropertyPS.setLong(2, assetId)
          textPropertyPS.setString(3, name)
          textPropertyPS.addBatch()

          (assetId, points.head)
        }

        assetPS.executeBatch()
        lrmPositionPS.executeBatch()
        assetLinkPS.executeBatch()
        singleChoicePS.executeBatch()
        textPropertyPS.executeBatch()

        assetGeometries.foreach { case (assetId, point) => updateAssetGeometry(assetId, point) }

        println(s"*** Imported ${railwayCrossings.length} railway crossings in ${humanReadableDurationSince(startTime)} (done ${i + 1}/$totalGroupCount)" )
      }
      assetPS.close()
      lrmPositionPS.close()
      assetLinkPS.close()
      singleChoicePS.close()
      textPropertyPS.close()
    }
  }

  def importDirectionalTrafficSigns(database: DatabaseDef, vvhServiceHost: String): Unit = {
    val query = sql"""
         select s.segm_id, s.tielinkki_id, t.mml_id, t.kunta_nro, to_2d(sdo_lrs.dynamic_segment(t.shape, s.alkum, s.loppum)),  s.alkum, s.loppum, s.puoli, s.opas_teksti
           from segm_opastaulu s
           join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
        """

    val directionalTrafficSigns = database.withDynSession {
      query.as[(Long, Long, Long, Int, Seq[Point], Double, Double, Int, String)].list
    }.groupBy(_._1).values.toList

    val roadLinks = new VVHClient(vvhServiceHost).fetchVVHRoadlinks(directionalTrafficSigns.map(_.head._3).toSet)
    val groupSize = 3000
    val groupedTrafficSigns = directionalTrafficSigns.grouped(groupSize).toList
    val totalGroupCount = groupedTrafficSigns.length

    OracleDatabase.withDynTransaction {
      val textPropertyId = sql"""select id from property where public_id = 'opastustaulun_teksti'""".as[Long].first

      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, MUNICIPALITY_CODE, FLOATING, CREATED_DATE, CREATED_BY) values (?, ?, ?, ?, SYSDATE, 'dr1_conversion')")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, ROAD_LINK_ID, MML_ID, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?, ?)")
      val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")
      val textPropertyPS =  dynamicSession.prepareStatement(s"insert into text_property_value (id, asset_id, property_id, value_fi) values (?, ?, $textPropertyId, ?)")

      println(s"*** Importing ${directionalTrafficSigns.length} directional traffic signs in $totalGroupCount groups of $groupSize each")

      groupedTrafficSigns.zipWithIndex.foreach { case (directionalTrafficSign, i) =>
        val startTime = DateTime.now()

        val assetGeometries = directionalTrafficSign.map { rows =>
          val (_, roadLinkId, mmlId, municipalityCode, points, startMeasure, endMeasure, sideCode, _) = rows.head
          val texts = rows.map(_._9)
          val assetId = Sequences.nextPrimaryKeySeqValue
          assetPS.setLong(1, assetId)
          assetPS.setInt(2, 240)
          assetPS.setInt(3, municipalityCode)
          val pointAsset = ImportedPointAsset(assetId, mmlId, startMeasure, false, points.head.x, points.head.y, municipalityCode)
          assetPS.setBoolean(4, PointAssetOperations.isFloating(
            pointAsset,
            roadLinks.find(_.mmlId == mmlId).map { x => (x.municipalityCode, x.geometry) }
          ))
          assetPS.addBatch()

          val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
          lrmPositionPS.setLong(1, lrmPositionId)
          lrmPositionPS.setLong(2, roadLinkId)
          lrmPositionPS.setLong(3, mmlId)
          lrmPositionPS.setDouble(4, startMeasure)
          lrmPositionPS.setDouble(5, endMeasure)
          lrmPositionPS.setInt(6, sideCode)
          lrmPositionPS.addBatch()

          assetLinkPS.setLong(1, assetId)
          assetLinkPS.setLong(2, lrmPositionId)
          assetLinkPS.addBatch()

          val id = Sequences.nextPrimaryKeySeqValue
          textPropertyPS.setLong(1, id)
          textPropertyPS.setLong(2, assetId)
          textPropertyPS.setString(3, texts.mkString("\n"))
          textPropertyPS.addBatch()

          (assetId, points.head)
        }

        assetPS.executeBatch()
        lrmPositionPS.executeBatch()
        assetLinkPS.executeBatch()
        textPropertyPS.executeBatch()

        assetGeometries.foreach { case (assetId, point) => updateAssetGeometry(assetId, point) }

        println(s"*** Imported ${directionalTrafficSign.length} directional traffic signs in ${humanReadableDurationSince(startTime)} (done ${i + 1}/$totalGroupCount)" )
      }
      assetPS.close()
      lrmPositionPS.close()
      assetLinkPS.close()
      textPropertyPS.close()
    }
  }
  def importServicePoints(database: DatabaseDef, vvhServiceHost: String): Unit =  {

    val query = sql"""
                            select p.palv_tyyppi, p.palv_lisatieto, p.palv_rautatieaseman_tyyppi, p.palv_paikkojen_lukumaara, p.palv_lepoalue_tyyppi, to_2d(p.shape), p.dr1_oid, p.nimi_fi
                              from palvelupisteet p
                           """

    val servicePoints = database.withDynSession {
      query.as[(Int, Option[String], Option[Int], Option[Int], Option[Int], Seq[Point], Long, Option[String])].list
    }.groupBy(_._1).values.toList

    val groupSize = 3000
    val groupedServicePoints = servicePoints.grouped(groupSize).toList
    val totalGroupCount = groupedServicePoints.length

    OracleDatabase.withDynTransaction {

      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, CREATED_DATE, CREATED_BY) values (?, ?, SYSDATE, 'dr1_conversion')")
      val servicePointPS = dynamicSession.prepareStatement("insert into service_point_value (ID,ASSET_ID,TYPE,ADDITIONAL_INFO,NAME,TYPE_EXTENSION) values (?,?,?,?,?,?)")
      println(s"*** Importing ${servicePoints.length} service points in $totalGroupCount groups of $groupSize each")

      groupedServicePoints.zipWithIndex.foreach { case (servicePoint, i) =>
        val startTime = DateTime.now()

        val assetId = Sequences.nextPrimaryKeySeqValue

        assetPS.setLong(1, assetId)
        assetPS.setInt(2, 250)

        assetPS.addBatch()

        servicePoint.foreach { case (servicePointType, additionalInfo, railwayStationType, _, restAreaType, geometry, name ) =>

          servicePointPS.setLong(1,Sequences.nextPrimaryKeySeqValue)
          servicePointPS.setLong(2, assetId)
          servicePointPS.setInt(3, servicePointType)
          servicePointPS.setString(4, additionalInfo)
          servicePointPS.setString(5, name)

        }

        assetPS.executeBatch()

        println(s"*** Imported ${servicePoint.length} directional traffic signs in ${humanReadableDurationSince(startTime)} (done ${i + 1}/$totalGroupCount)" )
      }
      assetPS.close()

    }


  }

}
