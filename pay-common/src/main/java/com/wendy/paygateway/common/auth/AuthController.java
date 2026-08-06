package com.wendy.paygateway.common.auth;

import com.wendy.paygateway.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Token endpoint for upstream business systems.
 *
 * <p>The sandbox simplifies this to "tell me your system name and get a token". A real gateway
 * would verify appId + signature here, or use the OAuth2 client-credentials flow.
 */
@Tag(name = "00-Auth", description = "Upstream business systems obtain an access token")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;

    @Operation(summary = "Obtain an access token",
            description = "Once pay.auth.enabled is on, the pay/refund endpoints require Authorization: Bearer <token>")
    @PostMapping("/token")
    public R<Map<String, String>> token(@RequestParam String bizSystem,
                                        @RequestParam(defaultValue = "demo-app") String appId) {
        Map<String, String> result = new HashMap<>();
        result.put("tokenType", "Bearer");
        result.put("accessToken", jwtUtils.issue(bizSystem, appId));
        return R.ok(result);
    }
}
