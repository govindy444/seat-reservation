package com.example.seats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats.api.ApiException;
import com.example.seats.config.AppProperties;

/**
 * Stand-in identity provider: issues a signed token for a user id (no passwords — out of scope).
 * Everything downstream trusts only the token's {@code sub}, never a user id in a request body.
 */
@RestController
public class AuthController {

    private final JwtEncoder encoder;
    private final AppProperties props;

    public AuthController(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    @PostMapping("/auth/token")
    TokenResponse issue(@Valid @RequestBody TokenRequest req) {
        List<String> roles = new ArrayList<>(List.of("user"));
        if (req.adminKey() != null) {
            if (!constantTimeEquals(req.adminKey(), props.adminKey())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "invalid admin key");
            }
            roles.add("admin");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(req.userId())
                .issuedAt(now)
                .expiresAt(now.plus(props.jwt().ttl()))
                .claim("scope", String.join(" ", roles))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", props.jwt().ttl().toSeconds(), req.userId(), roles);
    }

    /** Echoes who the server thinks you are — handy for checking that identity is token-derived. */
    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("user_id", jwt.getSubject(), "scope", jwt.getClaimAsString("scope"));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
