package com.yongyongthon.buscollector.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** bus_location / bus_arrival 배치 저장. 파라미터 순서는 컬럼 목록과 같다. */
@Repository
public class SnapshotRepository {

    public static final String LOCATION_COLUMNS = """
            call_log_id, collected_at, query_time, route_id, route_name, plate_no, veh_id, station_seq,
            station_id, state_cd, remain_seat_cnt, low_plate, crowded, route_type_cd, tagless_cd""";

    public static final String ARRIVAL_COLUMNS = """
            call_log_id, collected_at, query_time, station_id, route_id, route_name, route_type_cd,
            arrival_order, plate_no, veh_id, predict_time_min, predict_time_sec, location_no, remain_seat_cnt,
            low_plate, crowded, state_cd, current_station, flag, sta_order, route_dest_name, tagless_cd""";

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertLocations(List<Object[]> rows) {
        insert("bus_location", LOCATION_COLUMNS, rows);
    }

    public void insertArrivals(List<Object[]> rows) {
        insert("bus_arrival", ARRIVAL_COLUMNS, rows);
    }

    private void insert(String table, String columns, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int n = columns.split(",").length;
        String marks = String.join(",", java.util.Collections.nCopies(n, "?"));
        jdbc.batchUpdate("INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")", rows);
    }
}
