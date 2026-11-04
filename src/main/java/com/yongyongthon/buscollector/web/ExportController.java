package com.yongyongthon.buscollector.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 분석 팀용 CSV 내보내기와 미리보기. 둘은 같은 쿼리를 쓴다.
 * 예: GET /export?table=bus_location&date=2026-10-28, GET /export/preview?table=bus_location&date=2026-10-28&offset=0
 */
@RestController
public class ExportController {

    /** 내보낼 수 있는 테이블 → 날짜 필터 컬럼 (빈 문자열이면 전체) */
    private static final Map<String, String> TABLES = Map.of(
            "bus_location", "collected_at",
            "bus_arrival", "collected_at",
            "api_call_log", "called_at",
            "route_master", "",
            "route_station", "",
            "station_master", "");

    /** 기준표는 날짜가 없으니 페이지 넘김이 흔들리지 않도록 고정 정렬 */
    private static final Map<String, String> MASTER_ORDER = Map.of(
            "route_master", " ORDER BY job_name, route_name",
            "route_station", " ORDER BY route_id, station_seq",
            "station_master", " ORDER BY job_name, mobile_no");

    private static final String CALL_LOG_COLUMNS = "id, job_name, api_type, target_id, called_at, query_time, "
            + "http_status, result_code, result_msg, ok, attempts, duration_ms, row_count, error_msg";

    private static final int PREVIEW_MAX = 500;

    /** zip 으로 한 번에 받을 수 있는 최대 기간 */
    private static final int ZIP_MAX_DAYS = 92;

    private final JdbcTemplate jdbc;

    public ExportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 테이블·날짜로 만든 조회. from 은 "FROM ... WHERE ..." 부분 */
    private record Query(String columns, String from, String order, Object[] args) {
    }

    private static Query query(String table, LocalDate date, boolean raw) {
        String dateCol = TABLES.get(table);
        if (dateCol == null) {
            throw new IllegalArgumentException("table must be one of " + TABLES.keySet());
        }
        boolean byDate = !dateCol.isEmpty();
        if (byDate && date == null) {
            throw new IllegalArgumentException("date=yyyy-MM-dd is required for " + table);
        }
        String columns = "api_call_log".equals(table) && !raw ? CALL_LOG_COLUMNS : "*";
        if (!byDate) {
            return new Query(columns, " FROM " + table, MASTER_ORDER.get(table), new Object[0]);
        }
        return new Query(columns, " FROM " + table + " WHERE " + dateCol + " >= ? AND " + dateCol + " < ?", " ORDER BY id",
                new Object[]{Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay())});
    }

    @GetMapping("/export")
    public void export(@RequestParam String table,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(defaultValue = "false") boolean raw,
                       @RequestParam(defaultValue = "false") boolean excel,
                       HttpServletResponse res) throws IOException {
        Query q;
        try {
            q = query(table, date, raw);
        } catch (IllegalArgumentException e) {
            res.sendError(400, e.getMessage());
            return;
        }
        String filename = table + (q.args().length > 0 ? "_" + date : "") + ".csv";
        res.setContentType("text/csv; charset=UTF-8");
        res.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        PrintWriter out = res.getWriter();
        writeCsv(q, out, excel);
        out.flush();
    }

    /**
     * 기간의 일자별 CSV 를 zip 하나로. 날짜 있는 표는 하루 한 파일(행이 없는 날은 건너뜀), 기준표는 한 파일.
     * 예: GET /export/zip?from=2026-10-28&to=2026-11-13&tables=bus_location,bus_arrival,route_station
     */
    @GetMapping("/export/zip")
    public void exportZip(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam List<String> tables,
                          @RequestParam(defaultValue = "false") boolean excel,
                          HttpServletResponse res) throws IOException {
        if (to.isBefore(from) || from.plusDays(ZIP_MAX_DAYS - 1).isBefore(to)) {
            res.sendError(400, "from <= to, 최대 " + ZIP_MAX_DAYS + "일");
            return;
        }
        if (tables.isEmpty() || !TABLES.keySet().containsAll(tables)) {
            res.sendError(400, "tables must be some of " + TABLES.keySet());
            return;
        }
        res.setContentType("application/zip");
        res.setHeader("Content-Disposition", "attachment; filename=\"busdata_" + from + "_" + to + ".zip\"");
        ZipOutputStream zip = new ZipOutputStream(res.getOutputStream(), StandardCharsets.UTF_8);
        // 항목마다 닫지 않고 flush 만 한다 (닫으면 zip 스트림까지 닫힘)
        PrintWriter out = new PrintWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (String table : tables) {
            if (TABLES.get(table).isEmpty()) {
                zip.putNextEntry(new ZipEntry(table + ".csv"));
                writeCsv(query(table, null, false), out, excel);
                out.flush();
                zip.closeEntry();
                continue;
            }
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                Query q = query(table, day, false);
                Long rows = jdbc.queryForObject("SELECT COUNT(*)" + q.from(), Long.class, q.args());
                if (rows == null || rows == 0) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(table + "_" + day + ".csv"));
                writeCsv(q, out, excel);
                out.flush();
                zip.closeEntry();
            }
        }
        zip.finish();
        zip.flush();
    }

    private void writeCsv(Query q, PrintWriter out, boolean excel) {
        String sql = "SELECT " + q.columns() + q.from() + q.order();
        if (excel) {
            out.write('﻿');
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(1000);
            for (int i = 0; i < q.args().length; i++) {
                ps.setObject(i + 1, q.args()[i]);
            }
            return ps;
        }, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                header.append(i > 1 ? "," : "").append(md.getColumnLabel(i));
            }
            out.println(header);
            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        line.append(',');
                    }
                    line.append(csv(rs.getString(i)));
                }
                out.println(line);
            }
            return null;
        });
    }

    /** CSV 와 같은 내용을 JSON 으로 일부만: { columns, rows, total, offset, limit } */
    @GetMapping("/export/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @RequestParam String table,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean raw,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        Query q;
        try {
            q = query(table, date, raw);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        int lim = Math.min(Math.max(limit, 1), PREVIEW_MAX);
        int off = Math.max(offset, 0);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + q.from(), Long.class, q.args());

        Object[] args = new Object[q.args().length + 2];
        System.arraycopy(q.args(), 0, args, 0, q.args().length);
        args[args.length - 2] = lim;
        args[args.length - 1] = off;
        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        jdbc.query("SELECT " + q.columns() + q.from() + q.order() + " LIMIT ? OFFSET ?", rs -> {
            // 0행이어도 컬럼명은 메타데이터로 채운다
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 1; i <= n; i++) {
                columns.add(md.getColumnLabel(i));
            }
            while (rs.next()) {
                List<String> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
            return null;
        }, args);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("table", table);
        body.put("date", date);
        body.put("columns", columns);
        body.put("rows", rows);
        body.put("total", total);
        body.put("offset", off);
        body.put("limit", lim);
        return ResponseEntity.ok(body);
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
