package org.phoebus.olog.identity;

import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phoebus.olog.OlogAdministratorService;
import org.phoebus.olog.entity.Logbook;
import org.phoebus.olog.identity.api.IdentityApi;
import org.phoebus.olog.identity.client.ApiClient;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OlogAdministratorServiceTest {
    private static final String ENTITLEMENT = "i:infn:lnf:divisione_ricerca:servizio_infrastrutturale_calcolo_e_reti:reparto_sviluppo_applicazioni::n:resp";
    private MockRestServiceServer server;
    private OlogAdministratorService administrators;
    private ExperimentMembershipService identity;
    private UsernamePasswordAuthenticationToken user;

    @BeforeEach void setup() {
        var template = new RestTemplate();
        server = MockRestServiceServer.bindTo(template).build();
        var client = new ApiClient(template).setBasePath("https://identity.example/identity");
        client.setBearerToken("test-bearer");
        var factory = mock(InfnIdentityClientFactory.class);
        when(factory.create("test-bearer")).thenReturn(new IdentityApi(client));
        identity = new ExperimentMembershipService(factory);
        administrators = new OlogAdministratorService(identity);
        user = new UsernamePasswordAuthenticationToken("user-uuid", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-bearer");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"isMemberOf", "eduPersonEntitlement"})
    void exactEntitlementGrantsAdministratorAccess(String field) {
        server.expect(requestTo("https://identity.example/identity/get/auth/user-uuid"))
                .andExpect(header("Authorization", "Bearer test-bearer"))
                .andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"" + field + "\":[\"" + ENTITLEMENT + "\"]}}", MediaType.APPLICATION_JSON));
        assertTrue(administrators.isAdministrator(user));
        assertTrue(administrators.isAdministrator(user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ":extra", "onsabile", ":", "2"})
    void similarEntitlementsCannotEscalatePrivileges(String suffix) {
        String role = suffix.isEmpty() ? ENTITLEMENT.substring(0, ENTITLEMENT.length() - 1) : ENTITLEMENT + suffix;
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[\"" + role + "\"]}}", MediaType.APPLICATION_JSON));
        assertFalse(administrators.isAdministrator(user));
    }

    @Test void legacyAdminAuthoritiesDoNotReplaceTheInfnEntitlement() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[]}}", MediaType.APPLICATION_JSON));
        var legacy = new UsernamePasswordAuthenticationToken("user-uuid", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority(ENTITLEMENT)));
        assertFalse(administrators.isAdministrator(legacy));
    }

    @Test void unrelatedAuthorizationFieldsCannotGrantAdministration() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"schacUserStatus\":[\"" + ENTITLEMENT + "\"]}}", MediaType.APPLICATION_JSON));
        assertFalse(administrators.isAdministrator(user));
    }

    @Test void oneAuthorizationLookupServesExperimentMembershipAndAdminAccess() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[\"i:infn:lnf:csn1:atlas::aff:tng\"],\"eduPersonEntitlement\":[\""
                + ENTITLEMENT + "\"]}}", MediaType.APPLICATION_JSON));
        var book = new Logbook("ATLAS", "owner");
        book.setOfficialExperiment(true);
        book.setExperimentLdapPath("i:infn:lnf:csn1:atlas");
        assertTrue(identity.isMember(book, user));
        assertTrue(administrators.isAdministrator(user));
        assertTrue(identity.isMember(book, user));
    }

    @Test void missingTokenAndUnauthenticatedPrincipalsNeverGrantAccess() {
        assertFalse(administrators.isAdministrator(null));
        assertFalse(administrators.isAdministrator(new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new SimpleGrantedAuthority(ENTITLEMENT)))));
        assertFalse(administrators.isAdministrator(new UsernamePasswordAuthenticationToken("user-uuid", "test-bearer")));
        RequestContextHolder.resetRequestAttributes();
        assertFalse(administrators.isAdministrator(user));
    }

    @Test void identityFailureDoesNotGrantAccessAndIsCachedForTheRequest() {
        server.expect(anything()).andRespond(withServerError());
        assertFalse(administrators.isAdministrator(user));
        assertFalse(administrators.isAdministrator(user));
    }

    @Test void apiErrorsCannotGrantAccessEvenWhenThePayloadContainsTheRole() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":1,\"payload\":{\"isMemberOf\":[\"" + ENTITLEMENT + "\"]}}", MediaType.APPLICATION_JSON));
        assertFalse(administrators.isAdministrator(user));
    }
}
