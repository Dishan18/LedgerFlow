package com.ledgerflow.api.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints a merchant JWT so you can try the API from Swagger UI. Only exists with the
 * {@code local} profile; there is no such endpoint in any deployed environment.
 */
@Profile("local")
@RestController
public class DevTokenController {

    private final NimbusJwtEncoder encoder;

    public DevTokenController(LedgerflowProperties properties) {
        this.encoder = NimbusJwtEncoder.withSecretKey(SecurityConfig.jwtKey(properties)).build();
    }

    @PostMapping("/dev/token")
    public Map<String, String> token(@RequestParam UUID merchantId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(merchantId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(12)))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Map.of("access_token", token, "token_type", "Bearer");
    }
}
