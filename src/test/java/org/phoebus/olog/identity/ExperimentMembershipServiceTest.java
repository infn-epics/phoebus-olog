package org.phoebus.olog.identity;

import java.util.List;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.*;
import org.phoebus.olog.entity.Logbook;
import org.phoebus.olog.identity.api.IdentityApi;
import org.phoebus.olog.identity.client.ApiClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ExperimentMembershipServiceTest {
    static final String PATH = "i:infn:lnf:csn1:atlas";
    ExperimentMembershipService service;
    MockRestServiceServer server;
    Logbook book;
    UsernamePasswordAuthenticationToken principal;
    Logger logger;
    Level previousLevel;
    ListAppender<ILoggingEvent> events;

    @BeforeEach void setup() {
        logger = (Logger) LoggerFactory.getLogger(ExperimentMembershipService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.bindTo(template).build();
        ApiClient client = new ApiClient(template).setBasePath("https://identity.example/identity");
        client.setBearerToken("test-bearer");
        var factory = mock(InfnIdentityClientFactory.class);
        when(factory.create("test-bearer")).thenReturn(new IdentityApi(client));
        service = new ExperimentMembershipService(factory);
        book = new Logbook("ATLAS", "owner");
        book.setOfficialExperiment(true);
        book.setExperimentLdapPath(PATH);
        // Credentials have already been erased by Spring's AuthenticationManager.
        principal = new UsernamePasswordAuthenticationToken("user-uuid", null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer test-bearer");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void cleanup() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        events.stop();
        RequestContextHolder.resetRequestAttributes();
        server.verify();
    }

    @Test void fetchesIsMemberOfForAuthenticatedUuidAndReusesWithinRequest() {
        server.expect(requestTo("https://identity.example/identity/get/auth/user-uuid"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-bearer"))
                .andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[\"" + PATH + "::aff:tng\"]}}", MediaType.APPLICATION_JSON));
        assertTrue(service.isMember(book, principal));
        assertTrue(service.isMember(book, principal));
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage()
                .contains("requiredAffiliation='" + PATH + "::aff', membershipCount=1, matched=true")));
        Logbook other = new Logbook("CMS", "owner");
        other.setOfficialExperiment(true);
        other.setExperimentLdapPath("i:infn:lnf:csn1:cms");
        assertFalse(service.isMember(other, principal));
    }

    @Test void missingMembershipIsNotTakenFromOtherEntitlements() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"eduPersonEntitlement\":[\""
                + PATH + "::aff\"]}}", MediaType.APPLICATION_JSON));
        assertFalse(service.isMember(book, principal));
    }

    @Test void identityErrorsAreDeniedAndCachedForThisRequest() {
        server.expect(anything()).andRespond(withServerError().body("upstream failure: test-bearer"));
        assertFalse(service.isMember(book, principal));
        assertFalse(service.isMember(book, principal));
        assertEquals(1, events.list.stream().filter(event -> event.getLevel() == Level.WARN).count());
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("HTTP 500")));
        assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("test-bearer")));
    }

    @Test void apiErrorCodeCannotGrantAccess() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":1,\"payload\":{\"isMemberOf\":[\""
                + PATH + "::aff\"]}}", MediaType.APPLICATION_JSON));
        assertFalse(service.isMember(book, principal));
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("errorCode=1")));
    }

    @Test void withoutBearerNoIdentityRequestIsMade() {
        RequestContextHolder.resetRequestAttributes();
        assertFalse(service.isMember(book, principal));
        assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("no Bearer token")));
    }

    @Test void cacheDoesNotLeakBetweenRequests() {
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[\""
                + PATH + "::aff\"]}}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"errorCode\":0,\"payload\":{\"isMemberOf\":[]}}", MediaType.APPLICATION_JSON));
        assertTrue(service.isMember(book, principal));
        MockHttpServletRequest next = new MockHttpServletRequest();
        next.addHeader(HttpHeaders.AUTHORIZATION, "Bearer test-bearer");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(next));
        assertFalse(service.isMember(book, principal));
    }
}
