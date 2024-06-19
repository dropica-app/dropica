package backend

import sloth.LogHandler
import cats.effect.IO

import sloth.LogHandler

import scala.util.{Failure, Success}

import java.io.{PrintWriter, StringWriter}

object RpcLogHandlerAnsi extends LogHandler[IO] {
  def logRequest[A, T](
    path: List[String],
    argumentObject: A,
    result: IO[T],
  ): IO[T] = {
    val width = 250
    val plainArgString = pprint.PPrinter
      .BlackWhite(argumentObject, width = width, showFieldNames = false)
      .toString()
      .trim
      .replaceAll("\\s+", " ")

    val coloredArgString = pprint.apply(argumentObject, width = width, showFieldNames = false).toString()

    val apiName = path.mkString(".")
    println(s"${fansi.Color.Cyan(s"-> ${apiName}")}($coloredArgString)")
    result.attempt.timed.map { (duration, result) =>
      val durationMs = duration.toMillis

      result match {
        case r @ Right(res) =>
          val durationString = s"${durationMs}ms"
          val durationColored = if (durationMs < 100) {
            durationString
          } else if (durationMs < 500) {
            fansi.Color.Yellow(fansi.Bold.On(durationString))
          } else {
            fansi.Color.Red(fansi.Bold.On(durationString))
          }
          val call = res
          println(s"${fansi.Color.Yellow("<-")} $call [${durationColored}]")

          r
        case r @ Left(error) =>
          println(fansi.Color.Red(s"<- ${error.getMessage} [${durationMs}ms]"))
          error.printStackTrace()

          {
            val stacktrace = {
              val sw = new StringWriter
              error.printStackTrace(new PrintWriter(sw))
              sw.toString
            }
          }
          r
      }
    }.rethrow
  }
}
