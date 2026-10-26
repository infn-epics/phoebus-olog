package org.phoebus.olog;

import java.security.Principal;
import org.phoebus.olog.identity.ExperimentMembershipService;
import org.springframework.stereotype.Service;

/** The INFN responsibility entitlement identifying Olog administrators. */
@Service
public class OlogAdministratorService {
    public static final String ADMIN_ENTITLEMENT = "i:infn:lnf:divisione_ricerca:"
            + "servizio_infrastrutturale_calcolo_e_reti:reparto_sviluppo_applicazioni::n:resp";
    private final ExperimentMembershipService identityAuthorization;

    public OlogAdministratorService(ExperimentMembershipService identityAuthorization) {
        this.identityAuthorization = identityAuthorization;
    }

    public boolean isAdministrator(Principal principal) {
        return identityAuthorization.hasEntitlement(ADMIN_ENTITLEMENT, principal);
    }
}
