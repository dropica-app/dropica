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
import scala.annotation.nowarn
// import authn.frontend.authnJS.keratinAuthn.distTypesMod.Credentials

// Outwatch documentation: https://outwatch.github.io/docs/readme.html

object Main extends IOApp.Simple {
  def run = lift {

    val deviceSecret = unlift(RpcClient.getDeviceSecret).getOrElse(java.util.UUID.randomUUID().toString)
    localStorage.setItem("deviceSecret", deviceSecret)
    unlift(RpcClient.call.registerDevice(deviceSecret))

    // def getCurrentPositionPromise(options: PositionOptions): IO[Position] = IO.async_ { callback =>
    //   window.navigator.geolocation.getCurrentPosition(value => callback(Right(value)), error => callback(Left(Exception(error.message))))
    // }
    //
    // val locationEvents =
    //   Observable.intervalMillis(2000).mapEffect { _ =>
    //     getCurrentPositionPromise(
    //       js.Dynamic.literal(enableHighAccuracy = true, timeout = Double.PositiveInfinity, maximumAge = 0).asInstanceOf[PositionOptions]
    //     )
    //   }

    val refreshTrigger = VarEvent[Unit]()

    val myComponent = {
      // import webcodegen.shoelace.SlTab.*
      // import webcodegen.shoelace.SlTabGroup.*
      // import webcodegen.shoelace.SlTabPanel.*
      messagePanel(refreshTrigger, locationEvents)
      // slTabGroup(
      //   height := "100%",
      //   VMod.attr("placement") := "bottom",
      //   slTab("Messages", slotNav, panel := "messages"),
      //   slTab("Profile", slotNav, panel := "profile"),
      //   slTabPanel(
      //     name := "messages",
      //     messagePanel(refreshTrigger, locationEvents),
      //   ),
      //   slTabPanel(name := "profile", div(width := "100%", height := "100%", showDeviceAddress, addContact)),
      // )
    }

    // render the component into the <div id="app"></div> in index.html
    unlift(Outwatch.renderReplace[IO]("#app", myComponent, RenderConfig.showError))
  }

}

val positionEvents = RxEvent.observable(
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

val locationEvents = positionEvents.map(position =>
  rpc.Location(
    lat = position.coords.latitude,
    lon = position.coords.longitude,
    accuracy = position.coords.accuracy,
    altitude = position.coords.altitude,
    altitudeAccuracy = position.coords.altitudeAccuracy,
  )
)

def nextAccurateLocation(defaultLocation: rpc.Location): IO[rpc.Location] = {
  Observable
    .race(
      // any location with high accuracy will be immediately accepted
      locationEvents.observable.filter(_.accuracy <= 5),
      // or the most accurate location in the next 3 seconds, starting with defaultLocation
      locationEvents.observable
        // .take(1)
        .scan(defaultLocation)((min, next) => if (next.accuracy < min.accuracy) next else min)
        .sampleMillis(3000),
      // .takeUntil(Observable.fromEffect(IO.sleep(3.seconds))) // TODO colibri completion bug with takeUntil?

    )
    .headIO
}

def messagePanel(refreshTrigger: VarEvent[Unit], locationEvents: RxEvent[rpc.Location]) = {
  div(
    display.flex,
    flexDirection.column,
    paddingLeft := "5px",
    paddingRight := "5px",
    height := "100%",
    messagesNearby(refreshTrigger, locationEvents)(height := "50%"),
    div("On Device", textAlign.center, marginTop := "30px", color := "var(--sl-color-gray-600)"),
    messagesOnDevice(refreshTrigger, locationEvents)(height := "50%"),
    createMessageForm(refreshTrigger, locationEvents)(flexShrink := 0),
  )
}

def createMessageForm(refreshTrigger: VarEvent[Unit], locationEvents: RxEvent[rpc.Location]) = {
  import webcodegen.shoelace.SlButton.{value as _, *}
  import webcodegen.shoelace.SlButton
  import webcodegen.shoelace.SlTextarea.{value as _, *}
  import webcodegen.shoelace.SlTextarea

  val messageContentState = Var("")
  val errorState          = Var("")
  val loadingState        = Var(false)

  def submit(content: String, location: rpc.Location): IO[Unit] =
    lift[IO] {
      messageContentState.set("")
      if (unlift(RpcClient.call.createMessage(content.trim(), location)))
        errorState.set("")
      else
        errorState.set("Message already exists.")
      refreshTrigger.set(())
    }

  div(
    div(
      display.flex,
      slTextarea(
        placeholder := "type message",
        width := "100%",
        rows := 1,
        SlTextarea.resize := "auto", // TODO: jump in firefox -> shoelace bug?
        value <-- messageContentState,
        onSlChange.map(_.target.value) --> messageContentState,
      ),
      slButton(
        "create",
        loading <-- loadingState,
        SlButton.disabled := true,
        locationEvents.observable.head.map { location =>
          VMod(
            SlButton.disabled := false,
            onClick(messageContentState)
              .withEffect(lift[IO] {
                // TODO: one big lift block in onClick?
                loadingState.set(true)
                val accLoc = unlift(nextAccurateLocation(defaultLocation = location))
                loadingState.set(false)
                accLoc
              })
              .foreachEffect(submit),
          )
        },
      ),
    ),
    div(errorState, color := "var(--sl-color-gray-900)"),
    marginBottom := "10px",
  )
}

def messagesOnDevice(refreshTrigger: VarEvent[Unit], locationEvents: RxEvent[rpc.Location]) = {
  import webcodegen.shoelace.SlButton.{value as _, *}
  import webcodegen.shoelace.SlSelect.{onSlFocus as _, onSlBlur as _, onSlAfterHide as _, open as _, *}
  import webcodegen.shoelace.SlOption.{value as _, *}
  import webcodegen.shoelace.SlDialog.*
  import webcodegen.shoelace.SlDialog

  val contacts        = RxLater.effect(RpcClient.call.getContacts)
  val selectedProfile = VarLater[String]()

  div(
    display.flex,
    flexDirection.column,
    height := "100%",
    overflowY := "scroll",
    refreshTrigger.observable
      .prepend(())
      .asEffect(RpcClient.call.getMessagesOnDevice)
      .map(_.map { message =>
        val openDialog = Var(false)
        @nowarn
        val sendButton = VMod(
          slButton("send", onClick.as(true) --> openDialog),
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
        val dropButton = locationEvents.observable.head.map { location =>
          val loadingState = Var(false)
          slButton(
            "drop",
            loading <-- loadingState,
            onClick.stopPropagation.doEffect(
              lift[IO] {
                loadingState.set(true)
                val accLoc = unlift(nextAccurateLocation(defaultLocation = location))
                unlift(RpcClient.call.dropMessage(message.messageId, accLoc).void)
                loadingState.set(false)
                refreshTrigger.set(())
              }
            ),
          )
        }

      renderMessage(
        refreshTrigger,
        message,
        multiLine = true,
        actions = Some(VMod(dropButton)),
      )(marginTop := "8px")

      }),
  )
}

def messagesNearby(refreshTrigger: VarEvent[Unit], locationEvents: RxEvent[rpc.Location]) = {
  import webcodegen.shoelace.SlButton.{value as _, *}
  import webcodegen.shoelace.SlSpinner.*

  div(
    marginTop := "10px",
    display.flex,
    flexDirection.column,
    color := "var(--sl-color-gray-600)",
    div("Nearby", textAlign.center),
    div(
      display.flex,
      justifyContent.center,
      div(
        fontSize := "var(--sl-font-size-x-small)",
        locationEvents.observable
          .map(p =>
            div(
              f"Location Accuracy: ${p.accuracy}%.0fm"
            )
          )
          .prepend(slSpinner(fontSize := "3rem", marginTop := "30px")),
      ),
    ),
    div(
      overflowY := "scroll",
      Observable
        .intervalMillis(3000)
        .withLatest(locationEvents.observable)
        .switchMap { case (_, location) =>
          refreshTrigger.observable
            .prepend(())
            .asEffect(RpcClient.call.getMessagesAtLocation(location))
            .map(
              _.sortBy { case (message, messageLocation) => messageLocation.geodesicDistanceRangeTo(location).swap }.map {
                case (message, messageLocation) =>
                  renderMessage(
                    refreshTrigger,
                    message,
                    multiLine = false,
                    messageLocation = Some(messageLocation),
                    location = Some(location),
                    actions = Option.when(messageLocation.geodesicDistanceRangeTo(location)._1 < 10)(
                      slButton(
                        "pick",
                        onClick.stopPropagation.doEffect(
                          lift[IO] {
                            unlift(RpcClient.call.pickupMessage(message.messageId, location).void)
                            refreshTrigger.set(())
                          }
                        ),
                      )
                    ),
                  )(marginTop := "8px")
              }
            )
        },
    ),
  )
}

def renderMessage(
  refreshTrigger: VarEvent[Unit],
  message: rpc.Message,
  multiLine: Boolean,
  messageLocation: Option[rpc.Location] = None,
  location: Option[rpc.Location] = None,
  onClickEffect: Option[IO[Unit]] = None,
  actions: Option[VMod] = None,
) = {
  div(
    display.flex,
    alignItems.flexStart,
    backgroundColor := "var(--sl-color-gray-100)",
    color := "var(--sl-color-gray-900)",
    borderRadius := "5px",
    div(
      if (multiLine)
        VMod(
          message.content,
          VMod.style("white-space") := "break-spaces",
          wordWrap.breakWord,
        )
      else // only first line
        VMod(
          textOverflow.ellipsis,
          whiteSpace.pre,
          overflow.hidden,
          location.map(l =>
            messageLocation.map { ml =>
              // at 100m distance show 0 characters
              // at 0m show ~10 characters
              val range       = l.geodesicDistanceRangeTo(ml)
              val limitMeters = 100
              val emPerMeter  = 0.1
              val maxWidthEm  = (limitMeters - range._1.min(limitMeters)) * emPerMeter
              VMod(
                maxWidth := s"${maxWidthEm}em", // for low numbers, only shows ellipsis if there is enough space for it
                if (maxWidthEm > 1) message.content.takeWhile(_ != '\n') else "…",
              )
            }
          ),
        ),
      width := "100%",
      minWidth := "0", // fix, so that other items don't shrink
      padding := "16px",
      marginRight.auto,
    ),
    location.map(l =>
      messageLocation.map { ml =>
        val range = l.geodesicDistanceRangeTo(ml)

        div(f"${range._1}%.0f-${range._2}%.0fm", color := "var(--sl-color-gray-600)", padding := "16px", flexShrink := 0)
      }
    ),
    actions.map(actions => div(actions, flexShrink := 0, paddingTop := "5px", paddingRight := "5px")),
    onClickEffect match {
      case Some(onClickEffect) =>
        VMod(
          onClick.stopPropagation.mapEffect(_ => onClickEffect).as(()) --> refreshTrigger
        )
      case None => VMod.empty
    },
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
  import webcodegen.shoelace.SlInput

  val contactDeviceAddress = Var("")

  div(
    // camera,
    display.flex,
    slInput(
      // VMod.attr("size") := "large",
      // SlInput.size := "large",
      placeholder := "Public device id of contact",
      value <-- contactDeviceAddress,
      onSlChange.map(_.target.value) --> contactDeviceAddress,
    ),
    slButton("Add", onClick(contactDeviceAddress).foreachEffect(RpcClient.call.addContact(_).void)),
  )
}
