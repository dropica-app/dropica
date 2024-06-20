package frontend

import cats.effect.IO
import sloth.ext.jsdom.client.*
import org.scalajs.dom.window.localStorage

// import authn.frontend.AuthnClient
// import authn.frontend.AuthnClientConfig

object RpcClient {
  import chameleon.ext.upickle.given // TODO: Option as null

  // authn:
  // val headers: IO[Headers] = lift {
  //   val client     = AuthnClient[IO](AuthnClientConfig("http://localhost:3000"))
  //   val authHeader = unlift(client.session).map(token => Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  //   Headers(authHeader.toSeq)
  // }

  val getDeviceSecret = IO(Option(localStorage.getItem("deviceSecret")))

  private val headers: IO[Map[String, String]] = getDeviceSecret.map { secret =>
    secret.map(secret => "Authorization" -> s"Bearer $secret").toMap
  }
  private val httpConfig    = headers.map(headers => HttpRequestConfig(headers = headers))
  private val requestClient = sloth.Client[String, IO](HttpRpcTransport(httpConfig))

  val call: rpc.RpcApi = requestClient.wire[rpc.RpcApi]
}
