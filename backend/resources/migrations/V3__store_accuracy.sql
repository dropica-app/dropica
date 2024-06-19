alter table location rename to location_old;
drop trigger location_insert;
drop trigger location_update;
drop trigger location_delete;


create table location(
  location_id integer primary key, -- rowid
  lat real not null,
  lon real not null,
  accuracy real not null,
  altitude real not null,
  altitude_accuracy real not null,
  -- https://en.wikipedia.org/wiki/web_mercator_projection
  x real not null generated always as (
      6378137.0 * (lon * pi() / 180.0)
  ), -- TODO: stored
  y real not null generated always as (
      6378137.0 * ln(tan((pi() / 4.0) + (lat * pi() / 360.0)))
  ) -- TODO: stored
) strict;

create trigger location_insert after insert on location begin
    insert into spatial_index(location_id, minx, maxx, miny, maxy)
    values (new.location_id, new.x, new.x, new.y, new.y);
end;

create trigger location_update after update on location begin
    update spatial_index
    set minx = new.x, maxx = new.x, miny = new.y, maxy = new.y
    where location_id = old.location_id;
end;

create trigger location_delete after delete on location begin
    delete from spatial_index where location_id = old.location_id;
end;


insert into location select location_id, lat, lon, 100 as accuracy, altitude, 0 as altitude_accuracy from location_old;
