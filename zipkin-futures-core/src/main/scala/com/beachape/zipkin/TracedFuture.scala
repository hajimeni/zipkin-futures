package com.beachape.zipkin

import com.beachape.zipkin.services.ZipkinServiceLike
import com.twitter.zipkin.gen.Span

import scala.concurrent.Future
import scala.util.{ Success, Failure }
import scala.util.control.NonFatal

/**
 * Helpers for Tracing futures with Zipkin.
 */
object TracedFuture {

  /**
   * Does tracing of a Option[Span] => Future[A] based on the given trace name and annotations (annotations are sent
   * at the beginning). The Option[Span] parameter is so that you can pass the span details to other systems in
   * your Future-producing function.
   *
   * This function expects that there is a parent [[Span]] in scope. Even if there is a [[Span]]
   * in scope, it may not be used as a Parent [[Span]] if it does not have the proper ids, namely
   * a span id and a trace id. In this case, a new scope with the given name and new ids will be used
   * and it will not have a parent id.
   *
   * If the in scope [[ZipkinServiceLike]] does not provide a [[Span]] for the in scope parentSpan [[Span]] (or
   * if it fails) your function will be handed a None.
   *
   * Note, the [[Span]] given to your function should not be mutated to affect tracing. It is a deep copy anyways, so
   * there will be no effect.
   *
   * Example:
   *
   * {{{
   * val myTracedFuture = TracedFuture("slowHttpCall") { maybeSpan =>
   *   val forwardHeaders = maybeSpan.fold(Seq.empty[(String,String)]){ toHttpHeaders }
   *   WS.url("myServer").withHeaders(forwardHeaders:_*)
   * }
   * }}}
   *
   * @param traceName the name for the span used for tracing
   * @param annotations variable list of annotations to send for tracing in the beginning (after the client sent
   *                    annotation)
   * @param f the function that will take an Option[Span] and produce a Future[A]. It is an Option[Span] because
   *          there is a chance the [[ZipkinServiceLike]] will either fail or not produce a [[Span]] at all due to
   *          filtering or sampling.
   */
  def apply[A](traceName: String, annotations: (String, String)*)(f: Option[Span] => Future[A])(implicit parentSpan: Span, zipkinService: ZipkinServiceLike): Future[A] = {
    import zipkinService.eCtx // Because tracing-related tasks should use the same ExecutionContext
    endAnnotations(traceName, annotations: _*)(o => f(o).map((_, Seq.empty)))
  }

  /**
   * Does tracing of a Option[Span] => Future[(A, Seq[(String, String)]] based on the given trace name and annotations
   * (annotations are sent at the beginning). The Option[Span] parameter is so that you can pass the span details to
   * other systems in your Future-producing function. Note that this function expects the a Future [[Tuple2]] result,
   * where the second element in the pair is a Seq[(String, String)] set of annotations that you might want to use
   * in order to set final annotations on the span.
   *
   * This function expects that there is a parent [[Span]] in scope. Even if there is a [[Span]]
   * in scope, it may not be used as a Parent [[Span]] if it does not have the proper ids, namely
   * a span id and a trace id. In this case, a new scope with the given name and new ids will be used
   * and it will not have a parent id.
   *
   * If the in scope [[ZipkinServiceLike]] does not provide a [[Span]] for the in scope parentSpan [[Span]] (or
   * if it fails) your function will be handed a None.
   *
   * Note, the [[Span]] given to your function should not be mutated to affect tracing. It is a deep copy anyways, so
   * there will be no effect.
   *
   * Example:
   *
   * {{{
   * val myTracedFuture = TracedFuture.endAnnotations("slowHttpCall") { maybeSpan =>
   *   val forwardHeaders = maybeSpan.fold(Seq.empty[(String,String)]){ toHttpHeaders }
   *   WS.url("myServer").withHeaders(forwardHeaders:_*).map { response =>
   *      (response.json, Seq("session id" -> response.header("session id").toString))
   *   }
   * }
   * }}}
   *
   * @param traceName the name for the span used for tracing
   * @param annotations variable list of annotations to send for tracing in the beginning (after the client sent annotation)
   * @param f the function that will take an Option[Span] and produce a Future[(A, Seq[(String, String)])]. It is an
   *          Option[Span] because there is a chance the [[ZipkinServiceLike]] will either fail or not produce a
   *          [[Span]] at all due to filtering or sampling. The Seq[(String, String)] will be used as annotations
   *          to be sent right before setting client received and sending the Span.
   */
  def endAnnotations[A](traceName: String, annotations: (String, String)*)(f: Option[Span] => Future[(A, Seq[(String, String)])])(implicit parentSpan: Span, zipkinService: ZipkinServiceLike): Future[A] = {
    import zipkinService.eCtx // Because tracing-related tasks should use the same ExecutionContext
    val childSpan = zipkinService.generateSpan(traceName, parentSpan)
    val fMaybeSentCustomSpan = zipkinService.clientSent(childSpan, annotations: _*).
      recover { case NonFatal(e) => None }
    val fResult = for {
      maybeSentCustomSpan <- fMaybeSentCustomSpan
      maybeNormalSentSpan = maybeSentCustomSpan.map(c => zipkinService.clientSpanToSpan(c).deepCopy())
      result <- f(maybeNormalSentSpan)
    } yield result
    fMaybeSentCustomSpan foreach { maybeSentCustomSpan =>
      maybeSentCustomSpan foreach { sentCustomSpan =>
        fResult.onComplete {
          case Success((_, endAnnotations)) => zipkinService.clientReceived(sentCustomSpan, endAnnotations: _*)
          case Failure(e) => zipkinService.clientReceived(sentCustomSpan, "failed" -> s"Finished with exception: ${e.getMessage}")
        }
      }
    }
    fResult.map(_._1)
  }

}
