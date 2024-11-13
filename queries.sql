-- https://docs.sqlc.dev/en/stable/reference/query-annotations.html

-- name: registerDevice :exec
insert into device_profile
  (device_secret, device_address) values
  (?, ?)
