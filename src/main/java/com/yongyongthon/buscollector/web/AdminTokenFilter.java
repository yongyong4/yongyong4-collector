package com.yongyongthon.buscollector.web;

import com.yongyongthon.buscollector.config.CollectorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * ADMIN_TOKEN 이 설정되면 /admin/** (수집·변환 같은 조작)과 원본 JSON 내보내기(/export?raw=true)에
 * X-Admin-Token 헤더(또는 ?token=)를 요구한다. 일반 CSV·미리보기는 공개 데이터라 열어 둔다.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    private final String token;

    public AdminTokenFilter(CollectorProperties props) {
        this.token = props.adminToken() == null ? "" : props.adminToken().trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        boolean rawExport = uri.startsWith("/export") && "true".equalsIgnoreCase(req.getParameter("raw"));
        return token.isEmpty() || !(uri.startsWith("/admin") || rawExport);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader("X-Admin-Token");
        if (given == null) {
            given = req.getParameter("token");
        }
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "admin token required");
            return;
        }
        chain.doFilter(req, res);
    }
}
