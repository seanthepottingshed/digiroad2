package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.Modification
import org.json4s.{Formats, DefaultFormats}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Tag, FunSuite}
import org.scalatra.test.scalatest.ScalatraSuite
import org.apache.commons.codec.binary.Base64
import org.json4s.jackson.JsonMethods._


class IntegrationApiSpec extends FunSuite with ScalatraSuite {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def stopWithMmlId(mmlId: Long): MassTransitStopWithTimeStamps = {
    MassTransitStopWithTimeStamps(1L, 2L, 1.0, 2.0, None, false, Modification(None, None), Modification(None, None), Some(mmlId), None, Seq())
  }
  val mockMasstTransitStopService = MockitoSugar.mock[MassTransitStopService]
  when(mockMasstTransitStopService.getByMunicipality(235)).thenReturn(Seq(stopWithMmlId(123L), stopWithMmlId(321L)))
  addServlet(new IntegrationApi(mockMasstTransitStopService), "/*")

  def getWithBasicUserAuth[A](uri: String, username: String, password: String)(f: => A): A = {
    val credentials = username + ":" + password
    val encodedCredentials = Base64.encodeBase64URLSafeString(credentials.getBytes)
    val authorizationToken = "Basic " + encodedCredentials + "="
    get(uri, Seq.empty, Map("Authorization" -> authorizationToken))(f)
  }

  test("Should require correct authentication", Tag("db")) {
    get("/mass_transit_stops") {
      status should equal(401)
    }
    getWithBasicUserAuth("/mass_transit_stops", "nonexisting", "incorrect") {
      status should equal(401)
    }
  }

  test("Get assets requires municipality number") {
    getWithBasicUserAuth("/mass_transit_stops", "kalpa", "kalpa") {
      status should equal(400)
    }
    getWithBasicUserAuth("/mass_transit_stops?municipality=235", "kalpa", "kalpa") {
      status should equal(200)
    }
  }

  test("Returns mml id of the road link that the stop refers to") {
    getWithBasicUserAuth("/mass_transit_stops?municipality=235", "kalpa", "kalpa") {
      val mmlIds = (((parse(body) \ "features") \ "properties") \ "mml_id").extract[Seq[Long]]
      mmlIds should be(Seq(123L, 321L))
    }
  }
}
