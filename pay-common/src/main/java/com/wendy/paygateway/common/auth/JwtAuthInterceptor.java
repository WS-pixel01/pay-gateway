package com.wendy.paygateway.common.auth;

import com.wendy.paygateway.common.api.R;
import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.util.JsonUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Gateway authentication interceptor. Third-party webhook endpoints are excluded — they
 * authenticate via channel signature verification, not JWT.
 *
 * <p>Toggled by {@code pay.auth.enabled}, which defaults to off in the local sandbox so you can
 * curl the API directly.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_BIZ_SYSTEM = "X-BIZ-SYSTEM";

    private final JwtUtils jwtUtils;
    private final PayProperties payProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!payProperties.getAuth().isEnabled()) {
            return true;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        Claims claims = token == null ? null : jwtUtils.parse(token);
        if (claims == null) {
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(ATTR_BIZ_SYSTEM, claims.getSubject());
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtils.toJson(R.fail(ErrorCode.UNAUTHORIZED)));
    }
}
