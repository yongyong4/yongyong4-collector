package com.yongyongthon.buscollector.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class MasterRepository {

    public record RouteRow(String jobName, String routeId, String routeName, String region, String startStation,
                           String endStation, Integer routeTypeCd, String company, String note, String rawJson) {
    }

    public record RouteStationRow(String routeId, int stationSeq, String stationId, String stationName,
                                  String mobileNo, String region, String turnYn, Double x, Double y) {
    }

    public record StationRow(String jobName, String stationId, String mobileNo, String stationName, String region,
                             Double x, Double y, String directionNote) {
    }

    public record Target(String id, String name) {
    }

    private final JdbcTemplate jdbc;

    public MasterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertRoute(RouteRow r) {
        jdbc.update("""
                INSERT INTO route_master (job_name, route_id, route_name, region, start_station, end_station,
                    route_type_cd, company, note, raw_json, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE route_name = VALUES(route_name), region = VALUES(region),
                    start_station = VALUES(start_station), end_station = VALUES(end_station),
                    route_type_cd = VALUES(route_type_cd), company = VALUES(company), note = VALUES(note),
                    raw_json = VALUES(raw_json), updated_at = VALUES(updated_at)
                """, r.jobName(), r.routeId(), r.routeName(), r.region(), r.startStation(), r.endStation(),
                r.routeTypeCd(), r.company(), r.note(), r.rawJson(), now());
    }

    @Transactional
    public void replaceRouteStations(String routeId, List<RouteStationRow> rows) {
        jdbc.update("DELETE FROM route_station WHERE route_id = ?", routeId);
        Timestamp now = now();
        jdbc.batchUpdate("""
                INSERT INTO route_station (route_id, station_seq, station_id, station_name, mobile_no, region,
                    turn_yn, x, y, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, rows.stream().map(r -> new Object[]{r.routeId(), r.stationSeq(), r.stationId(),
                r.stationName(), r.mobileNo(), r.region(), r.turnYn(), r.x(), r.y(), now}).toList());
    }

    public void upsertStation(StationRow s) {
        jdbc.update("""
                INSERT INTO station_master (job_name, station_id, mobile_no, station_name, region, x, y,
                    direction_note, updated_at) VALUES (?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE mobile_no = VALUES(mobile_no), station_name = VALUES(station_name),
                    region = VALUES(region), x = VALUES(x), y = VALUES(y),
                    direction_note = VALUES(direction_note), updated_at = VALUES(updated_at)
                """, s.jobName(), s.stationId(), s.mobileNo(), s.stationName(), s.region(), s.x(), s.y(),
                s.directionNote(), now());
    }

    public void deleteStale(String job, List<String> keepRouteIds, List<String> keepStationIds) {
        deleteExcept("route_master", "route_id", job, keepRouteIds);
        deleteExcept("station_master", "station_id", job, keepStationIds);
    }

    private void deleteExcept(String table, String idColumn, String job, List<String> keep) {
        if (keep.isEmpty()) {
            jdbc.update("DELETE FROM " + table + " WHERE job_name = ?", job);
            return;
        }
        String marks = String.join(",", java.util.Collections.nCopies(keep.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(job);
        args.addAll(keep);
        jdbc.update("DELETE FROM " + table + " WHERE job_name = ? AND " + idColumn + " NOT IN (" + marks + ")",
                args.toArray());
    }

    public List<Target> routes(String job) {
        return jdbc.query("SELECT route_id, route_name FROM route_master WHERE job_name = ? ORDER BY route_name",
                (rs, i) -> new Target(rs.getString(1), rs.getString(2)), job);
    }

    public List<Target> stations(String job) {
        return jdbc.query("SELECT station_id, station_name FROM station_master WHERE job_name = ? ORDER BY mobile_no",
                (rs, i) -> new Target(rs.getString(1), rs.getString(2)), job);
    }

    public List<RouteStationRow> routeStations(String routeId) {
        return jdbc.query("""
                SELECT route_id, station_seq, station_id, station_name, mobile_no, region, turn_yn, x, y
                FROM route_station WHERE route_id = ? ORDER BY station_seq
                """, (rs, i) -> new RouteStationRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), (Double) rs.getObject(8), (Double) rs.getObject(9)),
                routeId);
    }

    public List<Map<String, Object>> overview() {
        return jdbc.queryForList("""
                SELECT 'route' AS kind, job_name, route_id AS id, route_name AS name,
                       CONCAT_WS(' → ', start_station, end_station) AS detail, region AS extra
                FROM route_master
                UNION ALL
                SELECT 'station', job_name, station_id, station_name, direction_note, mobile_no
                FROM station_master
                ORDER BY job_name, kind, name
                """);
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
