package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import java.util.Date
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult}


case class VProject(project_Id: Long, state: Integer, name: String, ely: Integer, created_By:String, created_date:Option[Date]=None,modified_By:String,modified_Date:Option[Date]=None)
case class ProjectLink(link_Id:Long,project_Id:Long,Road_Type:Integer,discontinuity_Type:Integer, road_Number:Long, road_Part_Number:Long,start_Addr_M:Double,end_Addr_M:Double,
                       lRM_Position_Id:Long,created_By:String,modified_By:String,created_Date:Option[Date],modified_Date:Option[Date])

class ProjectDao {



  implicit val getProjectMatch = new GetResult[VProject]{
    def apply(r: PositionedResult) = {
      val id=r.nextLong()
      val state=r.nextInt()
      val name=r.nextString()
      val ely=r.nextInt()
      val created_By=r.nextString()
      val created_date=r.nextDateOption()
      val modified_by=r.nextString()
      val modified_date=r.nextDateOption()
      VProject(id,state,name,ely,created_By,created_date,modified_by,modified_date)
    }
  }

  implicit val getProjectLinkMatch = new GetResult[ProjectLink]{
    def apply(r: PositionedResult) = {
      val link_Id=r.nextLong()
      val project_Id=r.nextLong()
      val discontinuity_Type=r.nextInt()
      val road_Type=r.nextInt()
      val road_Number=r.nextLong()
      val road_Part_Number=r.nextLong()
      val start_Addr_M=r.nextDouble()
      val end_Addr_M=r.nextDouble()
      val lrm_Position_ID=r.nextLong()
      val created_By=r.nextString()
      val modified_By=r.nextString()
      val created_Date=r.nextDateOption()
      val modifiedDate=r.nextDateOption()

      ProjectLink(link_Id,project_Id,road_Type,discontinuity_Type,road_Number, road_Part_Number, start_Addr_M, end_Addr_M, lrm_Position_ID, created_By, modified_By, created_Date, modifiedDate)
    }
  }
  def getProjects :Seq[VProject]={
      OracleDatabase.withDynTransaction {
        projectsQuarry
      }
      }

  def getProjectLinksByProjectID(project:Long):Seq[ProjectLink] ={
    OracleDatabase.withDynTransaction {
      projectLinksQuarry(project)
    }
  }

  def projectsQuarry: Seq[VProject] =
  {
    sql"""SELECT id,state, name, ely,created_By,created_Date,modified_By,modified_Date FROM Project""".as[VProject].list
  }

  def projectLinksQuarry(project:Long):Seq[ProjectLink]={
    sql"""SELECT id, project_id,road_type,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,lrm_position_id,created_by,modified_by,created_date,modified_date  FROM Project_link Where project_id = $project""".as[(ProjectLink)].list
  }
  }



