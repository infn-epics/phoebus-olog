package org.phoebus.olog.identity;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class InfnIdentityClientFactoryTest {
    @Test
    void defaultGatewayIncludesIdentityServicePath() {
        verifyRequest(Map.of(), "https://api.app.infn.it/identity/get/auth/user-uuid");
    }

    @Test
    void configuredGatewayPreservesCustomServicePath() {
        verifyRequest(Map.of("infn.identity.base-url", "https://identity.example/custom/identity"),
                "https://identity.example/custom/identity/get/auth/user-uuid");
    }

    private void verifyRequest(Map<String, Object> properties, String expectedUrl) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.register(InfnIdentityClientFactory.class);
            context.refresh();
            var api = context.getBean(InfnIdentityClientFactory.class).create("test-token");
            RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(api.getApiClient(), "restTemplate");
            var server = MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo(expectedUrl)).andExpect(header("Authorization", "Bearer test-token"))
                    .andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[\"i:infn:lnf:csn1:atlas::aff:tng\"]}}",
                            MediaType.APPLICATION_JSON));
            assertEquals("i:infn:lnf:csn1:atlas::aff:tng",
                    api.getAuthorizationForUUID("user-uuid").getPayload().getIsMemberOf().get(0));
            server.verify();
        }
    }
}
