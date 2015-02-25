package com.beachape.zipkin

import com.twitter.zipkin.gen.Span
import play.api.mvc.RequestHeader

import scala.language.implicitConversions
import scala.util.Try

object Implicits {

  implicit def req2span(req: RequestHeader): Span = {
    val span = new Span
    import HttpHeaders._
    def ghettoBind(headerKey: Value): Option[Long] = for {
      idString <- req.headers.get(headerKey.toString)
      id <- Try(idString.toLong).toOption
    } yield id
    ghettoBind(TraceIdHeaderKey).foreach(span.setTrace_id)
    ghettoBind(SpanIdHeaderKey).foreach(span.setId)
    ghettoBind(ParentIdHeaderKey).foreach(span.setParent_id)
    span
  }

}
