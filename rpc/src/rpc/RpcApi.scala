package rpc

import cats.effect.IO
import upickle.default.ReadWriter
import java.security.SecureRandom

trait RpcApi {
  def registerDevice(deviceSecret: String): IO[Unit]

  def getDeviceAddress: IO[String]
  def getMessagesOnDevice: IO[Vector[Message]]
  def getMessagesAtLocation(location: Location): IO[Vector[(Message, Location)]]
  def getContacts: IO[Vector[String]]

  def sendMessage(messageId: Int, deviceAddress: String): IO[Boolean]
  def pickupMessage(messageId: Int, location: Location): IO[Boolean]
  def dropMessage(messageId: Int, location: Location): IO[Boolean]
  def createMessage(content: String): IO[Unit]
  def addContact(targetDeviceAddress: String): IO[Boolean]
}

case class Location(lat: Double, lon: Double, accuracy: Double, altitude: Double, altitudeAccuracy: Double) derives ReadWriter {
  def toWebMercator: WebMercatorLocation = {
    import math._
    val x = 6378137.0 * (lon * Pi / 180.0)
    val y = 6378137.0 * log(tan((Pi / 4.0) + (lat * Pi / 360.0)))
    WebMercatorLocation(x, y, accuracy, altitude, altitudeAccuracy)
  }

  def geodesicDistanceTo(that: Location): Double =
    import math._
    val R    = 6371000.0 // Earth radius in meters
    val dLat = (that.lat - this.lat) * Pi / 180.0
    val dLon = (that.lon - this.lon) * Pi / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(this.lat * Pi / 180.0) * cos(that.lat * Pi / 180.0) *
      sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    R * c
}

case class WebMercatorLocation(x: Double, y: Double, accuracy: Double, altitude: Double, altitudeAccuracy: Double)

case class Message(messageId: Int, content: String) derives ReadWriter

def generateSecureDeviceAddress(length: Int): String = {
  val random = new SecureRandom()
  IArray.tabulate(length)(_ => wordList(random.nextInt(wordList.length))).mkString("-")
}
