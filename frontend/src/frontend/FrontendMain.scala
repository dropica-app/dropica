package frontend

import cats.effect.IO
import cats.effect.IOApp
import outwatch.*
import outwatch.dsl.*
import colibri.*
import colibri.reactive.*
// import authn.frontend.*
import org.scalajs.dom.window.localStorage
import org.scalajs.dom
import org.scalajs.dom.{window, Position}
import scala.scalajs.js
import org.scalajs.dom.PositionOptions
// import authn.frontend.authnJS.keratinAuthn.distTypesMod.Credentials

extension (position: org.scalajs.dom.Position)
  def toRpc: rpc.Location = {
    rpc.Location(
      lat = position.coords.latitude,
      lon = position.coords.longitude,
      accuracy = position.coords.accuracy,
      altitude = position.coords.altitude,
      altitudeAccuracy = position.coords.altitudeAccuracy,
    )
  }

// Outwatch documentation: https://outwatch.github.io/docs/readme.html

object Main extends IOApp.Simple {
  def run = lift {

    val deviceSecret = unlift(RpcClient.getDeviceSecret).getOrElse(java.util.UUID.randomUUID().toString)
    localStorage.setItem("deviceSecret", deviceSecret)
    unlift(RpcClient.call.registerDevice(deviceSecret))

    val positionObservable = RxEvent.observable(
      Observable
        .create[dom.Position] { observer =>
          val watchId = window.navigator.geolocation.watchPosition(
            position => observer.unsafeOnNext(position),
            error => observer.unsafeOnError(Exception(error.message)),
            js.Dynamic.literal(enableHighAccuracy = true, timeout = Double.PositiveInfinity, maximumAge = 0).asInstanceOf[PositionOptions],
          )
          Cancelable(() => window.navigator.geolocation.clearWatch(watchId))
        }
    )

    // def getCurrentPositionPromise(options: PositionOptions): IO[Position] = IO.async_ { callback =>
    //   window.navigator.geolocation.getCurrentPosition(value => callback(Right(value)), error => callback(Left(Exception(error.message))))
    // }
    //
    // val positionObservable =
    //   Observable.intervalMillis(2000).mapEffect { _ =>
    //     getCurrentPositionPromise(
    //       js.Dynamic.literal(enableHighAccuracy = true, timeout = Double.PositiveInfinity, maximumAge = 0).asInstanceOf[PositionOptions]
    //     )
    //   }

    val refreshTrigger = VarEvent[Unit]()

    val myComponent = {
      import webcodegen.shoelace.SlTab.*
      import webcodegen.shoelace.SlTabGroup.*
      import webcodegen.shoelace.SlTabPanel.*
      slTabGroup(
        height := "100%",
        VMod.attr("placement") := "bottom",
        slTab("Nearby", slotNav, panel := "nearby"),
        slTab("Device", slotNav, panel := "device"),
        slTab("Contacts", slotNav, panel := "contacts"),
        slTabPanel(
          name := "nearby",
          messagesNearby(refreshTrigger, positionObservable),
        ),
        slTabPanel(
          name := "device",
          messagesOnDevice(refreshTrigger, positionObservable),
        ),
        slTabPanel(name := "contacts", div(width := "100%", height := "100%", showDeviceAddress, addContact)),
        // camera,
      )
    }

    // render the component into the <div id="app"></div> in index.html
    unlift(Outwatch.renderReplace[IO]("#app", myComponent, RenderConfig.showError))
  }
}

def createMessage(refreshTrigger: VarEvent[Unit]) = {
  import webcodegen.shoelace.SlButton.{value as _, *}
  import webcodegen.shoelace.SlInput.{value as _, *}

  val messageString = Var("").transformVarRead(rx => Rx.observableSync(rx.observable.merge(refreshTrigger.observable.as(""))))

  div(
    slInput(placeholder := "type message", value <-- messageString, onSlChange.map(_.target.value) --> messageString),
    slButton("create", onClick.mapEffect(_ => RpcClient.call.createMessage(messageString.now())).as(()) --> refreshTrigger),
  )
}

def showDeviceAddress = {
  import webcodegen.shoelace.SlCopyButton.{value as _, *}
  import webcodegen.shoelace.SlQrCode.*

  div(
    b("Your public device id"),
    RpcClient.call.getDeviceAddress.map { deviceAddress =>
      VMod(
        div(deviceAddress),
        slCopyButton(value := deviceAddress),
        slQrCode(value := deviceAddress),
      )
    },
  )
}

def addContact = {
  import webcodegen.shoelace.SlInput.{value as _, *}
  import webcodegen.shoelace.SlButton.{value as _, *}

  val contactDeviceAddress = Var("")

  div(
    display.flex,
    slInput(
      placeholder := "Public device id of contact",
      value <-- contactDeviceAddress,
      onSlChange.map(_.target.value) --> contactDeviceAddress,
    ),
    slButton("Add", onClick(contactDeviceAddress).foreachEffect(RpcClient.call.addContact(_).void)),
  )
}

def messagesNearby(refreshTrigger: VarEvent[Unit], positionEvents: RxEvent[dom.Position]) = {
  import webcodegen.shoelace.SlSpinner.*
  div(
    div(
      fontSize := "var(--sl-font-size-x-small)",
      positionEvents.observable
        .map(p =>
          div(
            f"Location Accuracy: ${p.coords.accuracy}%.0fm"
          )
        )
        .prepend(div("Waiting for location service...", slSpinner())),
    ),
    Observable.intervalMillis(3000).withLatest(positionEvents.observable).switchMap { case (_, position) =>
      refreshTrigger.observable
        .prepend(())
        .asEffect(RpcClient.call.getMessagesAtLocation(position.toRpc))
        .map(
          _.map { case (message, messageLocation) =>
            renderMessage(
              refreshTrigger,
              message,
              messageLocation = Some(messageLocation),
              location = Some(position.toRpc),
              onClickEffect = Some(RpcClient.call.pickupMessage(message.messageId, position.toRpc).void),
            )
          }
        )
    },
  )
}

def renderMessage(
  refreshTrigger: VarEvent[Unit],
  message: rpc.Message,
  messageLocation: Option[rpc.Location] = None,
  location: Option[rpc.Location] = None,
  onClickEffect: Option[IO[Unit]] = None,
) =
  div(
    display.flex,
    padding := "16px",
    margin := "8px",
    borderRadius := "5px",
    div(message.content),
    location.map(l =>
      messageLocation.map(ml => div(f"${l.geodesicDistanceTo(ml)}%.0fm", color := "var(--sl-color-sky-900)", marginLeft.auto))
    ),
    onClickEffect match {
      case Some(onClickEffect) =>
        VMod(
          backgroundColor := "var(--sl-color-sky-100)",
          onClick.mapEffect(_ => onClickEffect).as(()) --> refreshTrigger,
        )
      case None => VMod.empty
    },
  )

def messagesOnDevice(refreshTrigger: VarEvent[Unit], positionObservable: RxEvent[dom.Position]) = {
  import webcodegen.shoelace.SlButton.{value as _, *}
  import webcodegen.shoelace.SlSelect.{onSlFocus as _, onSlBlur as _, onSlAfterHide as _, open as _, *}
  import webcodegen.shoelace.SlOption.{value as _, *}
  import webcodegen.shoelace.SlDialog.*
  import webcodegen.shoelace.SlDialog

  val contacts = RxLater.effect(RpcClient.call.getContacts)

  val onDeviceMessagesStream = refreshTrigger.observable.prepend(()).asEffect(RpcClient.call.getMessagesOnDevice)

  val selectedProfile = VarLater[String]()

  div(
    checked := true,
    onDeviceMessagesStream.map(_.map { message =>
      val openDialog = Var(false)
      div(
        display.flex,
        positionObservable.map(Some.apply).observable.prepend(None).map { position =>
          renderMessage(
            refreshTrigger,
            message,
            onClickEffect = position.map(position => RpcClient.call.dropMessage(message.messageId, position.toRpc).void),
          )
        },
        slButton("Send to device", onClick.as(true) --> openDialog),
        slDialog(
          open <-- openDialog,
          onSlAfterHide.onlyOwnEvents.as(false) --> openDialog,
          div(
            b(message.content),
            height := "500px",
            slSelect(
              onSlChange.map(_.target.value).collect { case s: String => s } --> selectedProfile,
              contacts.map(_.map { deviceAddress =>
                slOption(value := deviceAddress, deviceAddress)
              }),
            ),
          ),
          div(
            slotFooter,
            display.flex,
            slButton("Send to contact", onClick(selectedProfile).foreachEffect(RpcClient.call.sendMessage(message.messageId, _).void)),
          ),
        ),
      )
    }),
    createMessage(refreshTrigger),
  )
}
