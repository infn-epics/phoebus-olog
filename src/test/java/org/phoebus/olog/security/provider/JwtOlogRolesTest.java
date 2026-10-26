package org.phoebus.olog.security.provider;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class JwtOlogRolesTest {
    @Test void verifiedRealmRolesReachOlogAuthorization() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        for (List<String> roles : List.of(List.of("olog-admins"), List.of("ordinary-user"))) {
            var provider = new JwtAuthenticationProvider();
            provider.issuerUri = "https://issuer.example";
            provider.claimsName = "preferred_username";
            var server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate")).build();
            server.expect(requestTo("https://issuer.example/.well-known/openid-configuration"))
                    .andRespond(withSuccess("{\"jwks_uri\":\"https://issuer.example/keys\"}", MediaType.APPLICATION_JSON));
            var publicKey = (RSAPublicKey) keys.getPublic();
            var encoder = Base64.getUrlEncoder().withoutPadding();
            byte[] modulus = publicKey.getModulus().toByteArray();
            if (modulus[0] == 0) modulus = Arrays.copyOfRange(modulus, 1, modulus.length);
            String jwks = "{\"keys\":[{\"kid\":\"test\",\"n\":\"" + encoder.encodeToString(modulus)
                    + "\",\"e\":\"" + encoder.encodeToString(publicKey.getPublicExponent().toByteArray()) + "\"}]}";
            server.expect(requestTo("https://issuer.example/keys")).andRespond(withSuccess(jwks, MediaType.APPLICATION_JSON));
            String token = Jwts.builder().setHeaderParam("kid", "test").setIssuer("https://issuer.example")
                    .claim("preferred_username", "user-uuid").claim("realm_access", Map.of("roles", roles))
                    .setExpiration(new Date(System.currentTimeMillis() + 60000)).signWith(keys.getPrivate()).compact();
            var authentication = provider.authenticate(new UsernamePasswordAuthenticationToken(null, token));
            assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
            assertEquals(roles.contains("olog-admins"), authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_OLOG-ADMINS")));
            server.verify();
        }
    }
}
