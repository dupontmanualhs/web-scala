package models.auth

import play.api.mvc._
import scalajdo.DataStore
import javax.jdo.JDOHelper
import play.mvc.Results.Redirect

class VisitRequest[A](val request: Request[A]) extends WrappedRequest[A](request) {
  implicit val visit: Visit = request.session.get("visit").flatMap(
      Visit.getByUuid(_)).filter(!_.isExpired).getOrElse(
          new Visit(System.currentTimeMillis + Visit.visitLength, None))
}

// TODO: we need a cache system
object VisitAction {
  def apply[A](p: BodyParser[A])(f: VisitRequest[A] => PlainResult) = {
    Action(p) ( request => {
      val pm = DataStore.pm
      pm.beginTransaction()
      val visitReq = new VisitRequest[A](request)
      val res = f(visitReq)
      if (JDOHelper.isDeleted(visitReq.visit)) {
        pm.commitTransactionAndClose()
        res.withNewSession
      } else {
        visitReq.visit.expiration = System.currentTimeMillis + Visit.visitLength
        pm.makePersistent(visitReq.visit)
        pm.commitTransactionAndClose()
        if (request.session.get(visitReq.visit.uuid).isDefined) res
        else res.withSession("visit" -> visitReq.visit.uuid)
      }
    })
  }

  def apply(f: VisitRequest[AnyContent] => PlainResult) = {
    apply[AnyContent](BodyParsers.parse.anyContent)(f)
  }
}

object Authenticated {
  def apply(f: User => VisitRequest[AnyContent] => PlainResult) = VisitAction( req => {
	req.visit.user match {
	  case None => {
	    req.visit.redirectUrl = req.path
	    DataStore.pm.makePersistent(req.visit)
	    Results.Redirect(controllers.routes.Auth.login()).flashing("message" -> "You must log in to view that page.")
	  }
	  case Some(user) => f(user)(req)
	}
  })
}

object Method {
  val GET = "GET"
  val POST = "POST"
}