package com.wendy.paygateway.common.auth;

import com.wendy.paygateway.common.config.PayProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT issuing and verification. Upstream business systems (mall, membership, ...) call the gateway
 * under their {@code bizSystem} identity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private final PayProperties payProperties;

    public String issue(String bizSystem, String appId) {
        long now = System.currentTimeMillis();
        long expire = now + payProperties.getAuth().getExpireMinutes() * 60_000L;
        return Jwts.builder()
                .subject(bizSystem)
                .claims(Map.of("appId", appId))
                .issuedAt(new Date(now))
                .expiration(new Date(expire))
                .signWith(key())
                .compact();
    }

    /** Returns null when parsing fails (expired or tampered); the interceptor turns that into a 401. */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            log.warn("[JWT] verification failed: {}", e.getMessage());
            return null;
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(payProperties.getAuth().getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
