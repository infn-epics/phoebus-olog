package org.phoebus.olog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.phoebus.olog.entity.Logbook;
import org.phoebus.olog.entity.LogbookMember;
import org.phoebus.olog.entity.LogbookPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogbookAccessServiceTest {

    @Mock
    private LogbookRepository logbookRepository;
    @Mock
    private LogbookMemberRepository memberRepository;

    private LogbookAccessService service;
    private final Principal owner = () -> "owner-uuid";

    @BeforeEach
    void setUp() {
        service = new LogbookAccessService(logbookRepository, memberRepository, org.mockito.Mockito.mock(org.phoebus.olog.identity.ExperimentMembershipService.class));
    }

    @Test
    void ownerCanAddReaderAndWriter() {
        when(logbookRepository.findById("operations"))
                .thenReturn(Optional.of(new Logbook("operations", owner.getName())));
        when(memberRepository.find("operations", owner.getName())).thenReturn(Optional.empty());
        when(memberRepository.find("operations", "member-uuid")).thenReturn(Optional.empty());
        when(memberRepository.save(org.mockito.ArgumentMatchers.any(LogbookMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LogbookMember member = service.addMember("operations", "member-uuid",
                EnumSet.of(LogbookPermission.READ, LogbookPermission.WRITE), owner);

        assertEquals(EnumSet.of(LogbookPermission.READ, LogbookPermission.WRITE), member.getPermissions());
        assertEquals("member-uuid", member.getInfnUuid());
    }

    @Test
    void nonAdminCannotAddMember() {
        when(logbookRepository.findById("operations"))
                .thenReturn(Optional.of(new Logbook("operations", owner.getName())));
        when(memberRepository.find("operations", "other-uuid")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.addMember("operations", "member-uuid",
                        EnumSet.of(LogbookPermission.READ), () -> "other-uuid"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void adminPermissionCannotBeDelegated() {
        when(logbookRepository.findById("operations"))
                .thenReturn(Optional.of(new Logbook("operations", owner.getName())));
        when(memberRepository.find("operations", owner.getName())).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.addMember("operations", "member-uuid",
                        EnumSet.of(LogbookPermission.ADMIN), owner));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void ownerCannotBeRemoved() {
        LogbookMember ownerMembership = new LogbookMember("operations", owner.getName(),
                EnumSet.allOf(LogbookPermission.class), owner.getName(), "now", "now");
        when(logbookRepository.findById("operations"))
                .thenReturn(Optional.of(new Logbook("operations", owner.getName())));
        when(memberRepository.find("operations", owner.getName())).thenReturn(Optional.of(ownerMembership));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.removeMember("operations", owner.getName(), owner));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void ownerHasAllEffectivePermissions() {
        when(logbookRepository.findById("operations"))
                .thenReturn(Optional.of(new Logbook("operations", owner.getName())));

        var permissions = service.permissionsFor("operations", owner);

        assertEquals(EnumSet.allOf(LogbookPermission.class), permissions.permissions());
        assertEquals(true, permissions.admin());
    }

    @Test
    void writerCanWriteButReaderCannot() {
        when(logbookRepository.findById("operations"))
                .thenReturn(Optional.of(new Logbook("operations", owner.getName())));
        when(memberRepository.find("operations", "writer-uuid"))
                .thenReturn(Optional.of(new LogbookMember("operations", "writer-uuid",
                        EnumSet.of(LogbookPermission.WRITE), owner.getName(), "now", "now")));
        when(memberRepository.find("operations", "reader-uuid"))
                .thenReturn(Optional.of(new LogbookMember("operations", "reader-uuid",
                        EnumSet.of(LogbookPermission.READ), owner.getName(), "now", "now")));

        service.requirePermission(java.util.List.of("operations"), LogbookPermission.WRITE,
                () -> "writer-uuid");
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requirePermission(java.util.List.of("operations"), LogbookPermission.WRITE,
                        () -> "reader-uuid"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }
}
