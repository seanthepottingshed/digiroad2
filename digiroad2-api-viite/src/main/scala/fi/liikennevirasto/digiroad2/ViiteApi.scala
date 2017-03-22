package fi.liikennevirasto.digiroad2

import java.text.SimpleDateFormat
import fi.liikennevirasto.digiroad2.asset._
import scala.util.parsing.json._
import fi.liikennevirasto.digiroad2.authentication.RequestHeaderAuthentication
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.user.UserProvider
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.RoadAddressService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{RoadAddressLink, RoadAddressLinkPartitioner}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.json4s._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{NotFound, _}
import org.slf4j.LoggerFactory

/**
  * Created by venholat on 25.8.2016.
  */

case class newAddressDataExtractor(sourceIds: Set[Long], targetIds: Set[Long], roadAddress: Seq[RoadAddressCreator])

case class newRoadAddressProject(name: String, startDate: String, additionalInfo: String, roadNumber: Long, startPart: Long, endPart: Long)

class ViiteApi(val roadLinkService: RoadLinkService, val vVHClient: VVHClient,
               val roadAddressService: RoadAddressService,
               val userProvider: UserProvider = Digiroad2Context.userProvider,
               val revision: String = Digiroad2Context.revision,
               val deploy_date: String = Digiroad2Context.deploy_date
               )
  extends ScalatraServlet
    with JacksonJsonSupport
    with CorsSupport
    with RequestHeaderAuthentication
    with GZipSupport {

  class Contains(r: Range) { def unapply(i: Int): Boolean = r contains i }

  val DrawMainRoadPartsOnly = 1
  val DrawRoadPartsOnly = 2
  val DrawPublicRoads = 3
  val DrawAllRoads = 4

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json") + "; charset=utf-8"
    try {
      authenticateForApi(request)(userProvider)
      if (request.isWrite && !userProvider.getCurrentUser().hasViiteWriteAccess) {
        halt(Unauthorized("No write permissions"))
      }
    } catch {
      case ise: IllegalStateException => halt(Unauthorized("Authentication error: " + ise.getMessage))
    }
    response.setHeader(Digiroad2Context.Digiroad2ServerOriginatedResponseHeader, "true")
  }

  get("/startupParameters") {
    val (east, north, zoom) = {
      val config = userProvider.getCurrentUser().configuration
      (config.east.map(_.toDouble), config.north.map(_.toDouble), config.zoom.map(_.toInt))
    }
    StartupParameters(east.getOrElse(390000), north.getOrElse(6900000), zoom.getOrElse(2), revision, deploy_date)
  }


  get("/roadlinks") {
    response.setHeader("Access-Control-Allow-Headers", "*")

    val user = userProvider.getCurrentUser()
    val municipalities: Set[Int] = if (user.isViiteUser()) Set() else user.configuration.authorizedMunicipalities

    val zoomLevel = chooseDrawType(params.getOrElse("zoom", "5"))

    params.get("bbox")
      .map(getRoadLinksFromVVH(municipalities, zoomLevel))
      .getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
  }

  get("/roadlinks/:linkId") {
    val linkId = params("linkId").toLong
    val roadLinks = roadAddressService.getRoadAddressLink(linkId)
    if (roadLinks.nonEmpty) {
      val roadLink = roadLinks.tail.foldLeft(roadLinks.head) { case (a, b) =>
        a.copy(startAddressM = Math.min(a.startAddressM, b.startAddressM), endAddressM = Math.max(a.endAddressM, b.endAddressM),
          startMValue = Math.min(a.startMValue, b.endMValue))
      }
      Map("middlePoint" -> GeometryUtils.calculatePointFromLinearReference(roadLink.geometry,
        roadLink.length / 2.0)) ++ roadAddressLinkToApi(roadLink)
    } else {
      NotFound("Road link with link ID " + linkId + " not found")
    }
  }

    get("/roadlinks/adjacent") {
        val data = JSON.parseFull(params.get("roadData").get).get.asInstanceOf[Map[String,Any]]
        val chainLinks = data.get("selectedLinks").get.asInstanceOf[Seq[Double]].map(rl => {
          rl.toLong
        }).toSet[Long]
        val linkId = data.get("linkId").get.asInstanceOf[Double].toLong
        val roadNumber = data.get("roadNumber").get.asInstanceOf[Double].toLong
        val roadPartNumber = data.get("roadPartNumber").get.asInstanceOf[Double].toLong
        val trackCode = data.get("trackCode").get.asInstanceOf[Double].toLong

        roadAddressService.getFloatingAdjacent(chainLinks, linkId, roadNumber, roadPartNumber, trackCode).map(roadAddressLinkToApi)
    }

  get("/roadlinks/multiSourceAdjacents") {
      val roadData = JSON.parseFull(params.get("roadData").get).get.asInstanceOf[Seq[Map[String,Any]]]
      if (roadData.isEmpty){
        Set.empty
      } else {
        val adjacents:Seq[RoadAddressLink] = {
          roadData.flatMap(rd => {
            val chainLinks = rd.get("selectedLinks").get.asInstanceOf[Seq[Double]].map(rl => {
              rl.toLong
            }).toSet[Long]
            val linkId = rd.get("linkId").get.asInstanceOf[Double].toLong
            val roadNumber = rd.get("roadNumber").get.asInstanceOf[Double].toLong
            val roadPartNumber = rd.get("roadPartNumber").get.asInstanceOf[Double].toLong
            val trackCode = rd.get("trackCode").get.asInstanceOf[Double].toLong
            roadAddressService.getFloatingAdjacent(chainLinks, linkId,
              roadNumber, roadPartNumber, trackCode, false)
          })
        }
        val linkIds: Seq[Long] = roadData.map(rd => rd.get("linkId").get.asInstanceOf[Double].toLong)
        val result = adjacents.filter(adj => {
          !linkIds.contains(adj.linkId)
        }).distinct
        result.map(roadAddressLinkToApi)
      }
  }
    /*Lists all the projects*/
    get("/projects") {
      // user validation?
      val projectD = new ProjectDao
      projectD.getProjects.map { project =>
        Map("Pid" -> project.project_Id,
          "state" -> project.state,
          "name" -> project.name,
          "ely" -> project.ely,
          "created_by" -> project.created_By,
          "created_date" -> {project.created_date match {
            case Some(date) =>  new SimpleDateFormat("dd.MM.YYYY").format(date)
            case None => null
          }},
          "modified_by" -> project.modified_By,
          "modified_date" -> {project.modified_Date match {
            case Some(date) => new SimpleDateFormat("dd.MM.YYYY").format(date)
            case None => null
          }},
          "info" -> project.info
        )
      }
    }
 /*Lists links reserved to project*/
    get("/projects/:pId") {
      // user validation?
      val pId = params("pId").toLong
      val projectD = new ProjectDao
      projectD.getProjectLinksByProjectID(pId).map { link =>
        Map("Pid" -> link.project_Id,
          "Lid" -> link.link_Id,
          "road_type" -> link.road_Type,
          "discontinuity_type" ->link.discontinuity_Type,
          "road_number" -> link.road_Number,
          "created_by" -> link.created_By,
          "modified_by" -> link.modified_By,
          "road_part_number" -> link.road_Part_Number,
          "start_addr_m" -> link.start_Addr_M,
          "end_addr_m" -> link.end_Addr_M,
          "lrm_position_id"-> link.lRM_Position_Id,
          "modified_date" -> {link.modified_Date match {
            case Some(date) => new SimpleDateFormat("dd.MM.YYYY").format(date)
            case None => null
          }},
          "created_date"-> {link.created_Date match {
            case Some(date) => new SimpleDateFormat("dd.MM.YYYY").format(date)
            case None => null
          }}
        )
      }
    }

  get("/roadlinks/transferRoadLink") {
    val (sources, targets) = roadlinksData()
    val user = userProvider.getCurrentUser()
    val result = roadAddressService.getRoadAddressAfterCalculation(sources, targets, user)
    result.map(roadAddressLinkToApi)
  }

  put("/roadlinks/roadaddress") {
    val test = parsedBody.extract[newAddressDataExtractor]
    val roadAddressData = test.roadAddress
    val sourceIds = test.sourceIds
    val targetIds = test.targetIds
    val roadAddresses = roadAddressData.map{ ra =>
      RoadAddress(ra.id, ra.roadNumber, ra.roadPartNumber, Track.apply(ra.trackCode), Discontinuity.apply(ra.discontinuity), ra.startAddressM, ra.endAddressM,
        Some(DateTime.now()), None, Option(ra.modifiedBy), ra.linkId, ra.startMValue, ra.endMValue,0, SideCode.apply(ra.sideCode), ra.calibrationPoints, false, ra.points)
    }
    roadAddressService.transferFloatingToGap(sourceIds, targetIds, roadAddresses)
  }

  put("/roadlinks/roadaddress/project/new"){
    val test = parsedBody.extract[newRoadAddressProject]
    val user = userProvider.getCurrentUser()
    val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")
    val roadAddressProject  = RoadAddressProject( 0, 1, test.name, user.username, "-", formatter.parseDateTime(test.startDate), DateTime.now(), test.additionalInfo, test.roadNumber, test.startPart, test.endPart)
    roadAddressService.saveRoadLinkProject(roadAddressProject)
  }

  private def roadlinksData(): (Seq[String], Seq[String]) = {
    val data = JSON.parseFull(params.get("data").get).get.asInstanceOf[Map[String,Any]]
    val sources = data.get("sourceLinkIds").get.asInstanceOf[Seq[String]]
    val targets = data.get("targetLinkIds").get.asInstanceOf[Seq[String]]
    (sources, targets)
  }

  private def getRoadLinksFromVVH(municipalities: Set[Int], zoomLevel: Int)(bbox: String): Seq[Seq[Map[String, Any]]] = {
    val boundingRectangle = constructBoundingRectangle(bbox)
    val viiteRoadLinks = zoomLevel match {
      //TODO: When well-performing solution for main parts and road parts is ready
      case DrawMainRoadPartsOnly =>
//        roadAddressService.getCoarseRoadParts(boundingRectangle, Seq((1, 99)), municipalities)
        Seq()
      case DrawRoadPartsOnly =>
//        roadAddressService.getRoadParts(boundingRectangle, Seq((1, 19999)), municipalities)
        Seq()
      case DrawPublicRoads => roadAddressService.getRoadAddressLinks(boundingRectangle, Seq((1, 19999), (40000,49999)), municipalities, publicRoads = false)
      case DrawAllRoads => roadAddressService.getRoadAddressLinks(boundingRectangle, Seq(), municipalities, everything = true)
      case _ => roadAddressService.getRoadAddressLinks(boundingRectangle, Seq((1, 19999)), municipalities)
    }

    val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(viiteRoadLinks)
    partitionedRoadLinks.map {
      _.map(roadAddressLinkToApi)
    }
  }

  private def chooseDrawType(zoomLevel: String) = {
    val C1 = new Contains(-10 to 3)
    val C2 = new Contains(4 to 5)
    val C3 = new Contains(6 to 10)
    val C4 = new Contains(10 to 16)
    try {
      val level: Int = zoomLevel.toInt
      level match {
        case C1() => DrawMainRoadPartsOnly
        case C2() => DrawRoadPartsOnly
        case C3() => DrawPublicRoads
        case C4() => DrawAllRoads
        case _ => DrawMainRoadPartsOnly
      }
    } catch {
      case ex: NumberFormatException => DrawMainRoadPartsOnly
    }
  }

  private[this] def constructBoundingRectangle(bbox: String) = {
    val BBOXList = bbox.split(",").map(_.toDouble)
    BoundingRectangle(Point(BBOXList(0), BBOXList(1)), Point(BBOXList(2), BBOXList(3)))
  }

  def roadAddressLinkToApi(roadAddressLink: RoadAddressLink): Map[String, Any] = {
    Map(
      "segmentId" -> roadAddressLink.id,
      "id" -> roadAddressLink.id,
      "linkId" -> roadAddressLink.linkId,
      "mmlId" -> roadAddressLink.attributes.get("MTKID"),
      "points" -> roadAddressLink.geometry,
      "calibrationPoints" -> Seq(calibrationPoint(roadAddressLink.geometry, roadAddressLink.startCalibrationPoint),
        calibrationPoint(roadAddressLink.geometry, roadAddressLink.endCalibrationPoint)),
      "administrativeClass" -> roadAddressLink.administrativeClass.toString,
      "roadClass" -> roadAddressService.roadClass(roadAddressLink),
      "roadType" -> roadAddressLink.roadType.displayValue,
      "modifiedAt" -> roadAddressLink.modifiedAt,
      "modifiedBy" -> roadAddressLink.modifiedBy,
      "municipalityCode" -> roadAddressLink.attributes.get("MUNICIPALITYCODE"),
      "roadNameFi" -> roadAddressLink.attributes.get("ROADNAME_FI"),
      "roadNameSe" -> roadAddressLink.attributes.get("ROADNAME_SE"),
      "roadNameSm" -> roadAddressLink.attributes.get("ROADNAME_SM"),
      "roadNumber" -> roadAddressLink.roadNumber,
      "roadPartNumber" -> roadAddressLink.roadPartNumber,
      "elyCode" -> roadAddressLink.elyCode,
      "trackCode" -> roadAddressLink.trackCode,
      "startAddressM" -> roadAddressLink.startAddressM,
      "endAddressM" -> roadAddressLink.endAddressM,
      "discontinuity" -> roadAddressLink.discontinuity,
      "startDate" -> roadAddressLink.startDate,
      "endDate" -> roadAddressLink.endDate,
      "anomaly" -> roadAddressLink.anomaly.value,
      "roadLinkType" -> roadAddressLink.roadLinkType.value,
      "constructionType" ->roadAddressLink.constructionType.value,
      "startMValue" -> roadAddressLink.startMValue,
      "endMValue" -> roadAddressLink.endMValue,
      "sideCode" -> roadAddressLink.sideCode.value,
      "linkType" -> roadAddressLink.linkType.value,
      "roadLinkSource" ->  roadAddressLink.roadLinkSource.value
    )
  }

  private def calibrationPoint(geometry: Seq[Point], calibrationPoint: Option[CalibrationPoint]) = {
    calibrationPoint match {
      case Some(point) =>
        Option(Seq(("point", GeometryUtils.calculatePointFromLinearReference(geometry, point.segmentMValue)), ("value", point.addressMValue)).toMap)
      case _ => None
    }
  }

  case class StartupParameters(lon: Double, lat: Double, zoom: Int, revision: String, deploy_date: String)

  get("/user/roles") {
    userProvider.getCurrentUser().configuration.roles
  }

}

