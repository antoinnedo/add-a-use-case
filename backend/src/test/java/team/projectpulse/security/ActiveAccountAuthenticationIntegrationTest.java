package team.projectpulse.security;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActiveAccountAuthenticationIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired EntityManager entityManager;
    @Value("${api.endpoint.base-url}") String baseUrl;

    @Test
    void sameSignedTokenIsRejectedOnceItsInstructorIsInactive() throws Exception {
        String token = encode(Map.of("userId", 1), Instant.now().plusSeconds(600));
        request(token).andExpect(status().isOk());

        this.entityManager.find(Instructor.class, 1).setEnabled(false);
        this.entityManager.flush();
        this.entityManager.clear();

        request(token).andExpect(status().isUnauthorized());
    }

    @Test
    void signedTokensNeedAValidExistingAccountAndMustStillPassExpiryAndSignatureChecks() throws Exception {
        Instant expiry = Instant.now().plusSeconds(600);
        request(encode(Map.of(), expiry)).andExpect(status().isUnauthorized());
        request(encode(Map.of("userId", "malformed"), expiry)).andExpect(status().isUnauthorized());
        request(encode(Map.of("userId", Integer.MAX_VALUE), expiry)).andExpect(status().isUnauthorized());
        request(encode(Map.of("userId", 1), Instant.now().minusSeconds(300))).andExpect(status().isUnauthorized());

        String[] parts = encode(Map.of("userId", 1), expiry).split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        request(String.join(".", parts)).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions request(String token) throws Exception {
        return this.mvc.perform(get(this.baseUrl + "/students/4").header(AUTHORIZATION, "Bearer " + token));
    }

    private String encode(Map<String, Object> extraClaims, Instant expiry) {
        var claims = JwtClaimsSet.builder().subject("b.wei@abc.edu")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry)
                .claim("authorities", "ROLE_admin ROLE_instructor");
        extraClaims.forEach(claims::claim);
        return this.encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }
}
