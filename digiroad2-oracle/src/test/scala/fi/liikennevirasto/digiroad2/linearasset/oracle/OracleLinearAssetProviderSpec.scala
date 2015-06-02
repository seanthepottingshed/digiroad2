package fi.liikennevirasto.digiroad2.linearasset.oracle

import fi.liikennevirasto.digiroad2._
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

import scala.language.implicitConversions

import org.scalatest.FunSuite
import org.scalatest.Matchers
import org.scalatest.Tag

import fi.liikennevirasto.digiroad2.asset.{UnknownLinkType, UnknownDirection, Municipality, BoundingRectangle}

class OracleLinearAssetProviderSpec extends FunSuite with Matchers {
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val provider = new OracleLinearAssetProvider(new DummyEventBus, mockRoadLinkService)

  val roadLink = VVHRoadLinkWithProperties(1105998302l, List(Point(0.0, 0.0), Point(120.0, 0.0)), 120.0, Municipality, 1, UnknownDirection, UnknownLinkType, None, None)
  when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(List(roadLink))

  when(mockRoadLinkService.fetchVVHRoadlink(362964704))
    .thenReturn(Some(VVHRoadlink(362964704l, 91,  List(Point(0.0, 0.0), Point(117.318, 0.0)), Municipality, UnknownDirection, FeatureClass.AllOthers)))
  when(mockRoadLinkService.fetchVVHRoadlink(362955345))
    .thenReturn(Some(VVHRoadlink(362955345l, 91,  List(Point(117.318, 0.0), Point(127.239, 0.0)), Municipality, UnknownDirection, FeatureClass.AllOthers)))
  when(mockRoadLinkService.fetchVVHRoadlink(362955339))
    .thenReturn(Some(VVHRoadlink(362955339l, 91,  List(Point(127.239, 0.0), Point(146.9, 0.0)), Municipality, UnknownDirection, FeatureClass.AllOthers)))

  test("load speed limits with spatial bounds", Tag("db")) {
    val speedLimits = provider.getSpeedLimits(BoundingRectangle(Point(374700, 6677595), Point(374750, 6677560)), municipalities = Set())
    speedLimits.size shouldBe 1
  }

  test("get speed limit endpoints by id", Tag("db")) {
    val speedLimit = provider.getSpeedLimit(200114)
    speedLimit.get.endpoints shouldBe Set(Point(0.0,0.0,0.0), Point(146.9,0.0,0.0))
  }

  test("should ignore speed limits with segments outside link geometry") {
    val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
    val provider = new OracleLinearAssetProvider(new DummyEventBus, mockRoadLinkService)
    val roadLink = VVHRoadLinkWithProperties(389010100, List(Point(0.0, 0.0), Point(80.0, 0.0)), 80.0, Municipality, 0, UnknownDirection, UnknownLinkType, None, None)
    val roadLink2 = VVHRoadLinkWithProperties(388551994, List(Point(80.0, 0.0), Point(110.0, 0.0)), 30.0, Municipality, 0, UnknownDirection, UnknownLinkType, None, None)
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(List(roadLink, roadLink2))
    val speedLimits = provider.getSpeedLimits(BoundingRectangle(Point(0.0, 0.0), Point(1.0, 1.0)), Set.empty)
    speedLimits.map(_.id) should be(Seq(200204))
  }
}
