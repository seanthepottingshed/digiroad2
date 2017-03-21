package fi.liikennevirasto.viite.dao
import java.sql.SQLException
import slick.jdbc.StaticQuery.interpolation
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession


class ProjectLinkDaoSpec  extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  def addprojects(): Unit = {
    sqlu"""insert into project (id,state,name,ely,created_by) VALUES (1,0,'testproject',1,'automatedtest')""".execute
    sqlu"""insert into project (id,state,name,ely,created_by) VALUES (2,0,'testproject2',1,'automatedtest')""".execute
  }

  test("Add two links that are not reserved") {
    OracleDatabase.withDynTransaction {
      addprojects()
      /*Insert links to project*/
      sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (1,1,1,0,1,1,1,1,20000286,'automatedtest')""".execute
      sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (2,2,1,0,1,1,1,1,20000287,'automatedtest')""".execute
      sql"""SELECT COUNT(*) FROM project_link WHERE created_by = 'automatedtest'""".as[Long].first should be(2L)
      dynamicSession.rollback()
    }
  }

  test("Add two links that are reserved") {
    OracleDatabase.withDynTransaction {
      addprojects()
      var completed = true
      /*Insert links to project*/
      sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (1,1,1,0,1,1,1,1,20000286,'automatedtest')""".execute
      try {
        sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (2,2,1,0,1,1,1,1,20000286,'automatedtest')""".execute
      } catch {
        case _: SQLException =>
          completed = false
      }
      sql"""SELECT COUNT(*) FROM project_link WHERE created_by = 'automatedtest'""".as[Long].first should be(1L)
      completed should be(false)
      dynamicSession.rollback()
    }
  }
  test("Search projects") {
    OracleDatabase.withDynTransaction {
      addprojects()
      val ProjectDao = new ProjectDao
      val projects = ProjectDao.projectsQuarry
      projects.size should be (2)
      projects.isEmpty should be (false)
      dynamicSession.rollback()
    }
  }

  test("Get Project links") {
    OracleDatabase.withDynTransaction {
      addprojects()
      sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (1,1,1,0,1,1,1,1,20000286,'automatedtest')""".execute
      sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (2,1,1,0,1,1,1,1,20000287,'automatedtest')""".execute
      sqlu"""insert into project_link (id,project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_M,end_addr_M,lrm_position_id,created_by) VALUES (3,2,1,0,1,1,1,1,20000288,'automatedtest')""".execute
      val ProjectDao = new ProjectDao
      val projectLinks = ProjectDao.projectLinksQuarry(1)
      projectLinks.isEmpty should be (false)
      projectLinks.size should be (2)
      dynamicSession.rollback()
    }
  }
}