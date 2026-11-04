package com.yongyongthon.buscollector.store;

import com.yongyongthon.buscollector.client.ApiResponse;
import com.yongyongthon.buscollector.config.ApiType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class CallLogRepository {

    private final JdbcTemplate jdbc;

    public CallLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출 결과를 원본과 함께 저장하고 id 를 돌려준다. row_count 는 파싱 후 {@link #finish} 로 채운다. */
    public long insert(String job, ApiType type, String targetId, ApiResponse r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO api_call_log (job_name, api_type, target_id, called_at, query_time, http_status,
                        result_code, result_msg, ok, attempts, duration_ms, raw_json, raw_text, error_msg)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, job);
            ps.setString(2, type.name());
            ps.setString(3, targetId);
            ps.setTimestamp(4, ts(r.calledAt()));
            ps.setTimestamp(5, ts(r.queryTime()));
            setInt(ps, 6, r.httpStatus());
            setInt(ps, 7, r.resultCode());
            ps.setString(8, cut(r.resultMsg(), 200));
            ps.setBoolean(9, r.ok());
            ps.setInt(10, r.attempts());
            ps.setInt(11, (int) r.durationMs());
            ps.setString(12, r.rawJson());
            ps.setString(13, r.rawText());
            ps.setString(14, cut(r.error(), 1000));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** 인증키 문제 등으로 호출 자체를 하지 않은 경우의 기록 */
    public void insertSkipped(String job, ApiType type, String targetId, LocalDateTime at, String reason) {
        jdbc.update("""
                INSERT INTO api_call_log (job_name, api_type, target_id, called_at, ok, attempts, error_msg)
                VALUES (?,?,?,?,FALSE,0,?)
                """, job, type.name(), targetId, ts(at), cut(reason, 1000));
    }

    public void finish(long id, int rowCount, String parseError) {
        if (parseError == null) {
            jdbc.update("UPDATE api_call_log SET row_count = ? WHERE id = ?", rowCount, id);
        } else {
            jdbc.update("UPDATE api_call_log SET row_count = ?, ok = FALSE, error_msg = ? WHERE id = ?",
                    rowCount, cut("저장 실패: " + parseError, 1000), id);
        }
    }

    /** 일일 한도 집계용: 오늘 해당 job 들(같은 인증키)·API 의 실제 호출 수(재시도 포함) */
    public int attemptsOn(List<String> jobs, ApiType type, LocalDate day) {
        if (jobs.isEmpty()) {
            return 0;
        }
        String marks = String.join(",", java.util.Collections.nCopies(jobs.size(), "?"));
        List<Object> args = new java.util.ArrayList<>(jobs);
        args.add(type.name());
        args.add(ts(day.atStartOfDay()));
        args.add(ts(day.plusDays(1).atStartOfDay()));
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(attempts), 0) FROM api_call_log WHERE job_name IN ("
                + marks + ") AND api_type = ? AND called_at >= ? AND called_at < ?", Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** API 종류별 호출 수 / 실패 수 / 저장 행 수 */
    public List<Map<String, Object>> dailySummary(LocalDate day) {
        return jdbc.queryForList("""
                SELECT job_name, api_type,
                       COUNT(*)                         AS calls,
                       COALESCE(SUM(attempts), 0)       AS attempts,
                       SUM(CASE WHEN ok THEN 0 ELSE 1 END) AS failures,
                       COALESCE(SUM(row_count), 0)      AS saved_rows,
                       MAX(CASE WHEN ok THEN called_at END) AS last_success_at
                FROM api_call_log
                WHERE called_at >= ? AND called_at < ?
                GROUP BY job_name, api_type
                ORDER BY job_name, api_type
                """, ts(day.atStartOfDay()), ts(day.plusDays(1).atStartOfDay()));
    }

    public List<Map<String, Object>> lastSuccess() {
        return jdbc.queryForList("""
                SELECT job_name, api_type, MAX(called_at) AS last_success_at
                FROM api_call_log WHERE ok = TRUE AND api_type IN ('LOCATION', 'ARRIVAL')
                GROUP BY job_name, api_type
                """);
    }

    public List<Map<String, Object>> recentErrors(int limit) {
        return jdbc.queryForList("""
                SELECT called_at, job_name, api_type, target_id, result_code, error_msg
                FROM api_call_log WHERE ok = FALSE ORDER BY id DESC LIMIT ?
                """, limit);
    }

    static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    static void setInt(PreparedStatement ps, int i, Integer v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
