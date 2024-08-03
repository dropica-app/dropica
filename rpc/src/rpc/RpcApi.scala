package rpc

import cats.effect.IO
import upickle.default.ReadWriter
import java.security.SecureRandom

trait RpcApi {
  def registerDevice(deviceSecret: String): IO[Unit]

  def getDeviceAddress: IO[String]
  def getMessagesAtLocation(location: Location, codeword: String): IO[Vector[(Message, Location)]]
  def getMessagesOnDevice: IO[Vector[Message]]

  def createMessage(content: String, location: Location, codeword: String): IO[Boolean]
  def pickupMessage(messageId: Int, location: Location, codeword: String): IO[Boolean]
  def dropMessage(messageId: Int, location: Location, codeword: String): IO[Boolean]

  def getContacts: IO[Vector[String]]
  def addContact(targetDeviceAddress: String): IO[Boolean]
  def sendMessage(messageId: Int, deviceAddress: String): IO[Boolean]
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

  def geodesicDistanceRangeTo(that: Location): (Double, Double) = {
    import math._
    val horizontalDistance = geodesicDistanceTo(that)
    val verticalDistance   = abs(this.altitude - that.altitude)
    val totalDistance      = sqrt(pow(horizontalDistance, 2) + pow(verticalDistance, 2))

    val horizontalRange = this.accuracy + that.accuracy
    val verticalRange   = this.altitudeAccuracy + that.altitudeAccuracy
    val totalRange      = sqrt(pow(horizontalRange, 2) + pow(verticalRange, 2))

    (max(0, totalDistance - totalRange), totalDistance + totalRange)
  }
}

case class WebMercatorLocation(x: Double, y: Double, accuracy: Double, altitude: Double, altitudeAccuracy: Double)

case class Message(messageId: Int, content: String) derives ReadWriter

def generateSecureDeviceAddress(length: Int): String = {
  val random = new SecureRandom()
  IArray.tabulate(length)(_ => wordList(random.nextInt(wordList.length))).mkString("-")
}
