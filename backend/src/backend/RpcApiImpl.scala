package backend

import cats.effect.IO
import org.http4s.Request
import org.http4s.headers.Authorization
import org.http4s.AuthScheme
import org.http4s.Credentials
import cats.implicits.*
import com.augustnagro.magnum
import com.augustnagro.magnum.*
import io.github.arainko.ducktape.*
import rpc.generateSecureDeviceAddress
import backend.db.LocationRepo
import javax.sql.DataSource
// import org.http4s.ember.client.EmberClientBuilder
// import cats.effect.unsafe.implicits.global // TODO
// import scala.util.control.NonFatal
// import scala.concurrent.duration.*

// import authn.backend.TokenVerifier
// import authn.backend.AuthnClient
// import authn.backend.AuthnClientConfig
// import authn.backend.AccountImport

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

private val threadPool                     = Executors.newFixedThreadPool(2)
private val dbec: ExecutionContextExecutor = ExecutionContext.fromExecutor(threadPool)

class RpcApiImpl(ds: DataSource, request: Request[IO]) extends rpc.RpcApi {

  val headers: Option[Authorization] = request.headers.get[Authorization]
  val deviceSecret: Option[String]   = headers.collect { case Authorization(Credentials.Token(AuthScheme.Bearer, secret)) => secret }

  // Authn integration
  // val headers: Option[Authorization] = request.headers.get[Authorization]
  // val token: Option[String]          = headers.collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token }
  // val httpClient = EmberClientBuilder.default[IO].withTimeout(44.seconds).build.allocated.map(_._1).unsafeRunSync() // TODO not forever
  // val authnClient = AuthnClient[IO](
  //   AuthnClientConfig(
  //     issuer = "http://localhost:3000",
  //     audiences = Set("localhost"),
  //     username = "admin",
  //     password = "adminpw",
  //     adminURL = Some("http://localhost:3001"),
  //   ),
  //   httpClient = httpClient,
  // )
  // val verifier = TokenVerifier[IO]("http://localhost:3000", Set("localhost"))
  // def userAccountId: IO[Option[String]] = token.traverse(token => verifier.verify(token).map(_.accountId))
  // def withUser[T](code: String => IO[T]): IO[T] = userAccountId.flatMap {
  //   case Some(accountId) => code(accountId)
  //   case None            => IO.raiseError(Exception("403 Unauthorized"))
  // }
  //

  def withDevice[T](code: db.DeviceProfile => IO[T]): IO[T] = deviceSecret match {
    case Some(deviceSecret) =>
      magnum.connect(ds) {
        db.DeviceProfileRepo.findByIndexOnDeviceSecret(deviceSecret)
      } match {
        case Some(profile) => code(profile)
        case None          => IO.raiseError(Exception("403 Unauthorized"))
      }

    case None => IO.raiseError(Exception("403 Unauthorized"))
  }

  def registerDevice(deviceSecret: String): IO[Unit] = IO {
    magnum.connect(ds) {
      db.DeviceProfileRepo.findByIndexOnDeviceSecret(deviceSecret) match {
        case Some(_) => ()
        case None =>
          val creator = db.DeviceProfile.Creator(
            deviceSecret = deviceSecret,
            deviceAddress = generateSecureDeviceAddress(10),
          )
          val _ = sql"""
          insert into ${db.DeviceProfile.Table}(${db.DeviceProfile.Table.deviceSecret}, ${db.DeviceProfile.Table.deviceAddress}) values (${creator})
          """.update.run()
      }
    }
  }.evalOn(dbec)

  def sendMessage(messageId: Int, targetDeviceAddress: String): IO[Boolean] = withDevice(deviceProfile =>
    IO {
      magnum.transact(ds) {
        lift[Option] {
          val targetDeviceProfile = unlift(db.DeviceProfileRepo.findByIndexOnDeviceAddress(targetDeviceAddress))
          val message             = unlift(db.MessageRepo.findById(messageId))
          db.MessageRepo.update(message.copy(onDevice = Some(targetDeviceProfile.deviceId)))
          db.MessageHistoryRepo.insert(
            db.MessageHistory.Creator(messageId = message.messageId, onDevice = Some(targetDeviceProfile.deviceId), atLocation = None)
          )
          scribe.info(s"message $messageId sent to $targetDeviceAddress")
          true
        }.getOrElse(false)
      }
    }
  )

  def dropMessage(messageId: Int, location: rpc.Location): IO[Boolean] = withDevice(deviceProfile =>
    IO {
      // you can only drop it, if it's on your device
      magnum.transact(ds) {
        val message = db.MessageRepo.findById(messageId).get
        val targetLocation = db.LocationRepo.insertReturning(
          db.Location.Creator(location.lat, location.lon, location.accuracy, location.altitude, location.altitudeAccuracy)
        )
        val messageIsOnDevice = message.onDevice.contains(deviceProfile.deviceId)
        scribe.info(s"message $messageId is on device: $messageIsOnDevice, moving to ${targetLocation.toString}")
        if (messageIsOnDevice) {
          db.MessageRepo.update(message.copy(onDevice = None, atLocation = Some(targetLocation.locationId)))
          db.MessageHistoryRepo.insert(
            db.MessageHistory.Creator(messageId = message.messageId, onDevice = None, atLocation = Some(targetLocation.locationId))
          )
          scribe.info(s"dropped $message at $location")
          true
        } else {
          scribe.info(s"could not drop $message at $location")
          false
        }
      }
    }
  )

  def pickupMessage(messageId: Int, location: rpc.Location): IO[Boolean] = withDevice(deviceProfile =>
    IO {
      // you can only pick up a message, if it is close to your location
      magnum.transact(ds) {
        val message            = db.MessageRepo.findById(messageId).get
        val messagesAtLocation = queryNearbyMessages(location)
        if (messagesAtLocation.exists(_._1.messageId == messageId)) {
          db.MessageRepo.update(message.copy(onDevice = Some(deviceProfile.deviceId), atLocation = None))
          db.MessageHistoryRepo.insert(
            db.MessageHistory.Creator(messageId = message.messageId, onDevice = Some(deviceProfile.deviceId), atLocation = None)
          )
          true
        } else {
          false
        }
      }
    }
  )

  def createMessage(content: String, location: rpc.Location): IO[Boolean] = withDevice(deviceProfile =>
    IO {
      magnum.transact(ds) {
        val targetLocation = db.LocationRepo.insertReturning(
          db.Location.Creator(location.lat, location.lon, location.accuracy, location.altitude, location.altitudeAccuracy)
        )
        Either.catchNonFatal(
          db.MessageRepo.insert(db.Message.Creator(content, onDevice = None, atLocation = Some(targetLocation.locationId)))
        ) match {
          case Left(e) =>
            scribe.error(e)
            false
          case Right(_) => true
        }
      }
    }
  )

  def getMessagesOnDevice: IO[Vector[rpc.Message]] = withDevice(deviceProfile =>
    IO {
      magnum.connect(ds) {
        val dbMessages = db.MessageRepo.findByIndexOnOnDevice(Some(deviceProfile.deviceId))
        dbMessages.map(_.to[rpc.Message])
      }
    }
  )

  val maxSearchRadiusMeters = 40_075 // circumference of earth
  val messageLimit          = 200
  private def queryNearbyMessages(location: rpc.Location, searchRadiusMeters: Long = 500)(using
    DbCon
  ): Vector[(rpc.Message, rpc.Location)] = {
    val locationWebMercator = location.toWebMercator
    // TODO coordinate wrap around at date line
    val locations = sql"""
      WITH params AS (
        SELECT
          6378137.0 AS R, -- Earth's radius in meters for Web Mercator
          ${locationWebMercator.x} AS target_x, -- Web Mercator x-coordinate of the target
          ${locationWebMercator.y} AS target_y, -- Web Mercator y-coordinate of the target
          ${location.lat} AS target_latitude,
          ${location.lon} AS target_longitude,
          ${searchRadiusMeters} AS search_radius -- Adjust this value as needed
      ),
      candidates AS (
        SELECT
          l.*
        FROM
          location l,
          params p
        JOIN
          spatial_index si
        ON
          l.location_id = si.location_id
        WHERE
          si.minx <= p.target_x + p.search_radius AND
          si.maxx >= p.target_x - p.search_radius AND
          si.miny <= p.target_y + p.search_radius AND
          si.maxy >= p.target_y - p.search_radius
      )
      SELECT
        c.*,
        (p.R * acos(
          cos(radians(p.target_latitude)) * cos(radians(c.lat)) * cos(radians(c.lon) - radians(p.target_longitude)) +
          sin(radians(p.target_latitude)) * sin(radians(c.lat))
        )) AS distance
      FROM
        candidates c,
        params p
      WHERE
        (p.R * acos(
          cos(radians(p.target_latitude)) * cos(radians(c.lat)) * cos(radians(c.lon) - radians(p.target_longitude)) +
          sin(radians(p.target_latitude)) * sin(radians(c.lat))
        )) <= p.search_radius
      ORDER BY
        distance
      LIMIT ${messageLimit};
      """.query[db.Location].run()

    val rpcLocations = locations.view
      .flatMap(l => db.MessageRepo.findByIndexOnAtLocation(Some(l.locationId)).map(m => (m.to[rpc.Message], l.to[rpc.Location])))
      .toVector

    if (rpcLocations.nonEmpty || searchRadiusMeters > maxSearchRadiusMeters)
      // we got what we wanted
      rpcLocations
    else {
      scribe.info(
        s"searching at ${location.lat},${location.lon} with radius ${searchRadiusMeters}m only found ${rpcLocations.size} messages"
      )
      // if we didn't find enough messages in search radius, increase it
      queryNearbyMessages(location, searchRadiusMeters * 2)
    }
  }

  def getMessagesAtLocation(location: rpc.Location): IO[Vector[(rpc.Message, rpc.Location)]] = withDevice(deviceProfile =>
    IO {
      magnum.transact(ds) {
        queryNearbyMessages(location)
      }
    }
  )

  def getDeviceAddress: IO[String] = withDevice(deviceProfile => IO.pure(deviceProfile.deviceAddress))

  def addContact(contactDeviceAddress: String): IO[Boolean] = withDevice(deviceProfile =>
    IO {
      magnum.transact(ds) {
        db.DeviceProfileRepo.findByIndexOnDeviceAddress(contactDeviceAddress) match {
          case Some(contactDeviceProfile) =>
            db.ContactRepo.insert(db.Contact.Creator(deviceId = deviceProfile.deviceId, contactDeviceId = contactDeviceProfile.deviceId))
            true
          case None =>
            false
        }
      }
    }
  )

  override def getContacts: IO[Vector[String]] = withDevice { deviceProfile =>
    IO {
      magnum.connect(ds) {
        val contacts = db.ContactRepo.findByIndexOnDeviceId(deviceProfile.deviceId)
        contacts.flatMap(contact => db.DeviceProfileRepo.findById(contact.contactDeviceId)).map(_.deviceAddress)
      }
    }

  }
}
