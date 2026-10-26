package org.phoebus.olog.identity;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.phoebus.olog.entity.Logbook;
import org.phoebus.olog.identity.model.ApiResultResponseAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class ExperimentMembershipService {
    private static final Logger logger = LoggerFactory.getLogger(ExperimentMembershipService.class);
    private static final String CACHE_KEY = ExperimentMembershipService.class.getName() + ".authorization.";
    private final InfnIdentityClientFactory clientFactory;
    private record AuthorizationData(List<String> memberships, List<String> entitlements) {}
    private static final AuthorizationData EMPTY = new AuthorizationData(List.of(), List.of());

    public ExperimentMembershipService(InfnIdentityClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public boolean isMember(Logbook logbook, Principal principal) {
        String path = logbook.getExperimentLdapPath();
        if (!logbook.isOfficialExperiment() || path == null || path.isBlank()) {
            logger.debug("Experiment membership skipped for logbook '{}': officialExperiment={}, pathConfigured={}",
                    logbook.getName(), logbook.isOfficialExperiment(), path != null && !path.isBlank());
            return false;
        }
        String affiliation = path + "::aff";
        List<String> roles = authorizationFor(principal).memberships();
        boolean matched = roles.stream().anyMatch(role -> role != null
                && (role.equals(affiliation) || role.startsWith(affiliation + ":")));
        logger.debug("Experiment membership for logbook '{}': requiredAffiliation='{}', membershipCount={}, matched={}",
                logbook.getName(), affiliation, roles.size(), matched);
        return matched;
    }

    public boolean hasEntitlement(String entitlement, Principal principal) {
        if (principal == null || principal instanceof AnonymousAuthenticationToken
                || (principal instanceof Authentication && !((Authentication) principal).isAuthenticated())) {
            return false;
        }
        AuthorizationData data = authorizationFor(principal);
        return data.memberships().contains(entitlement) || data.entitlements().contains(entitlement);
    }

    private AuthorizationData authorizationFor(Principal principal) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        String cacheKey = CACHE_KEY + principal.getName();
        if (request != null) {
            AuthorizationData cached = (AuthorizationData) request.getAttribute(cacheKey, RequestAttributes.SCOPE_REQUEST);
            if (cached != null) return cached;
        }
        AuthorizationData data = fetchAuthorization(principal, request);
        if (request != null) request.setAttribute(cacheKey, data, RequestAttributes.SCOPE_REQUEST);
        return data;
    }

    private AuthorizationData fetchAuthorization(Principal principal, RequestAttributes request) {
        // ProviderManager may erase JWT credentials, so use the authenticated request's header.
        String token = null;
        if (request instanceof ServletRequestAttributes) {
            String header = ((ServletRequestAttributes) request).getRequest().getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7);
            }
        }
        if (token == null && principal instanceof Authentication) {
            Object credentials = ((Authentication) principal).getCredentials();
            if (credentials instanceof String && ((String) credentials).split("\\.").length == 3) {
                token = (String) credentials;
            }
        }
        if (token == null || token.isBlank()) {
            logger.warn("INFN authorization unavailable: no Bearer token on the authenticated request");
            return EMPTY;
        }
        try {
            ApiResultResponseAuthorization response = clientFactory.create(token)
                    .getAuthorizationForUUID(principal.getName());
            if (response != null && Integer.valueOf(0).equals(response.getErrorCode())
                    && response.getPayload() != null) {
                return new AuthorizationData(values(response.getPayload().getIsMemberOf()),
                        values(response.getPayload().getEduPersonEntitlement()));
            }
            logger.warn("INFN Identity returned invalid authorization data: errorCode={}, payloadPresent={}, isMemberOfPresent={}",
                    response == null ? null : response.getErrorCode(),
                    response != null && response.getPayload() != null,
                    response != null && response.getPayload() != null && response.getPayload().getIsMemberOf() != null);
        } catch (RestClientResponseException exception) {
            logger.warn("INFN Identity authorization lookup failed: HTTP {}. Check the Bearer token and that oauth2.claimsName identifies the INFN UUID",
                    exception.getRawStatusCode());
        } catch (RestClientException exception) {
            // Do not log the exception: upstream messages may contain credentials or personal data.
            logger.warn("Unable to retrieve authorization from INFN Identity: {}",
                    exception.getClass().getSimpleName());
        }
        // An unavailable identity service never grants access, but explicit logbook ACLs remain usable.
        return EMPTY;
    }

    private List<String> values(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
