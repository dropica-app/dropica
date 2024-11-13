// This file is generated from queries.sql using queries_template.go.tmpl
package backend.queries

import com.augustnagro.magnum
import com.augustnagro.magnum.*

def fragWriter(params: Seq[Any]): FragWriter = { (preparedStatement, startPos) =>
  var i = 0
  val n = params.size
  while (i < n) {
    val param = params(i)
    param match {
      case param: Int    => preparedStatement.setInt(startPos + i, param)
      case param: Long   => preparedStatement.setLong(startPos + i, param)
      case param: String => preparedStatement.setString(startPos + i, param)
      case param: Double => preparedStatement.setDouble(startPos + i, param)
    }
    i += 1
  }
  startPos + params.size
}

//  https://docs.sqlc.dev/en/stable/reference/query-annotations.html

def registerDevice(
  device_secret: String,
  device_address: String,
)(using con: DbCon): Unit = {
  val params = IArray(
    device_secret,
    device_address,
  )
  val result = Frag(
    """
  
insert into device_profile
  (device_secret, device_address) values
  (?, ?)
  """,
    params,
    fragWriter(params),
  ).update.run()
  println(s"queries.registerDevice(device_secret=${device_secret}, device_address=${device_address}) => ${result}")
  result
}
