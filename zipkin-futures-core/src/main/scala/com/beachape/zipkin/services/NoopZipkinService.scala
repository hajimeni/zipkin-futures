package com.beachape.zipkin.services

import com.twitter.zipkin.gen.{ Annotation, Span }
import scala.collection.JavaConverters._

import scala.concurrent.{ Future, ExecutionContext }

/**
 * Dummy ZipkinServiceLike that just returns true or false based on whether the [[Span]]s passed to it are
 * sendable to Zipkin
 */
object NoopZipkinService extends ZipkinServiceLike {

  type ServerSpan = Span
  type ClientSpan = Span

  implicit val eCtx = scala.concurrent.ExecutionContext.global

  def serverReceived(span: Span, annotations: (String, String)*): Future[Option[ServerSpan]] = {
    Future.successful {
      if (sendableSpan(span)) {
        span.addToAnnotations(new Annotation(System.currentTimeMillis(), "sr"))
        Some(span)
      } else None
    }
  }

  def clientSent(span: Span, annotations: (String, String)*): Future[Option[ClientSpan]] = {
    Future.successful {
      if (sendableSpan(span)) {
        span.addToAnnotations(new Annotation(System.currentTimeMillis(), "cs"))
        Some(span)
      } else None
    }
  }

  def serverSent(span: ServerSpan, annotations: (String, String)*): Future[Option[ServerSpan]] = {
    Future.successful {
      span.addToAnnotations(new Annotation(System.currentTimeMillis(), "ss"))
      Some(span)
    }
  }

  def clientReceived(span: ClientSpan, annotations: (String, String)*): Future[Option[ClientSpan]] = {
    Future.successful {
      span.addToAnnotations(new Annotation(System.currentTimeMillis(), "cr"))
      Some(span)
    }
  }

  def clientSpanToSpan(clientSpan: ClientSpan): Span = clientSpan

  def serverSpanToSpan(serverSpan: ClientSpan): Span = serverSpan
}
