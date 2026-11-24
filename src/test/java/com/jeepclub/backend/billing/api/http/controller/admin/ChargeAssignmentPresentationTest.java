package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.core.domain.model.ChargeDefinition;
import com.jeepclub.backend.billing.core.repository.ChargeDefinitionRepository;
import com.jeepclub.backend.publications.api.module.EventPresentationQuery;
import com.jeepclub.backend.billing.core.application.result.ChargeAssignmentResult;
import com.jeepclub.backend.billing.core.domain.enums.assignment.ChargeAudienceType;
import com.jeepclub.backend.iam.authorization.api.module.RolePresentationQuery;
import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChargeAssignmentPresentationTest {
    private final RolePresentationQuery roles = mock(RolePresentationQuery.class);
    private final UserQuery identity = mock(UserQuery.class);
    private final ChargeDefinitionRepository definitions = mock(ChargeDefinitionRepository.class);
    private final EventPresentationQuery events = mock(EventPresentationQuery.class);
    private final ChargeAssignmentPresentation presentation = new ChargeAssignmentPresentation(roles,
            new BillingUserNamePresentation(identity), definitions, events);

    private ChargeAssignmentResult assignment(long id, ChargeAudienceType type, Long roleId) {
        return new ChargeAssignmentResult(id, 1L, type, null, roleId, null, true, Instant.EPOCH, null);
    }
    private void authorize(boolean allowed) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", "n/a", allowed ? List.of(new SimpleGrantedAuthority("AUTHORIZATION_ROLE_READ")) : List.of()));
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void resolvesDistinctRolesInOneBatchAndHandlesMissingNames() {
        authorize(true);
        when(roles.findByIds(List.of(2L, 3L))).thenReturn(List.of(new RolePresentationQuery.Details(2L, "Diretoria")));
        var page = presentation.present(new PageImpl<>(List.of(assignment(1, ChargeAudienceType.ROLE, 2L), assignment(2, ChargeAudienceType.ROLE, 3L), assignment(3, ChargeAudienceType.ROLE, 2L), assignment(4, ChargeAudienceType.ALL_MEMBERS, null))));
        assertThat(page.getContent()).extracting("roleName").containsExactly("Diretoria", null, "Diretoria", null);
        assertThat(page.getContent().get(1).roleId()).isEqualTo(3L);
        verify(roles).findByIds(List.of(2L, 3L));
        verifyNoMoreInteractions(roles);
    }
    @Test void hidesRoleAndAvoidsQueryWhenUnauthorizedOrNoRoles() {
        authorize(false);
        var hidden = presentation.present(assignment(1, ChargeAudienceType.ROLE, 2L));
        assertThat(hidden.roleId()).isNull(); assertThat(hidden.roleName()).isNull();
        authorize(true);
        assertThat(presentation.present(new PageImpl<>(List.of(assignment(2, ChargeAudienceType.ALL_MEMBERS, null)))).getContent().get(0).roleName()).isNull();
        assertThat(presentation.present(new PageImpl<>(List.<ChargeAssignmentResult>of())).getContent()).isEmpty();
        verifyNoInteractions(roles);
    }

    @Test void resolvesEligibleNamesOncePerPageAndOnMutations() {
        var first = userAssignment(1L, 12L);
        var duplicate = userAssignment(2L, 12L);
        var inactive = userAssignment(3L, 13L);
        var absent = userAssignment(4L, 14L);
        var unnamed = userAssignment(5L, 15L);
        var rows = new PageImpl<>(List.of(first, duplicate, inactive, absent, unnamed));
        authorize(false);
        assertThat(presentation.present(rows).getContent()).extracting("userName").containsOnlyNulls();
        verifyNoInteractions(identity);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user", "n/a", List.of(new SimpleGrantedAuthority("IDENTITY_USER_READ"))));
        assertThat(presentation.present(new PageImpl<>(List.<ChargeAssignmentResult>of())).getContent()).isEmpty();
        verifyNoInteractions(identity);
        when(identity.findByIds(List.of(12L, 13L, 14L, 15L))).thenReturn(List.of(
                user(12L, "Nome atual", true), user(13L, "Oculto", false), user(15L, " ", true)));

        assertThat(presentation.present(rows).getContent()).extracting("userName")
                .containsExactly("Nome atual", "Nome atual", null, null, null);
        verify(identity).findByIds(List.of(12L, 13L, 14L, 15L));
        verifyNoMoreInteractions(identity);

        when(identity.findByIds(List.of(12L))).thenReturn(List.of(user(12L, "Nome atual", true)));
        assertThat(presentation.present(first).userName()).isEqualTo("Nome atual");
    }

    @Test void resolvesDefinitionAndOnlyLooksUpEventTitlesWithPermission() {
        var event = new ChargeAssignmentResult(1L, 1L, ChargeAudienceType.EVENT_PARTICIPANTS,
                null, null, 10L, true, Instant.EPOCH, null);
        var definition = mock(ChargeDefinition.class);
        when(definition.getId()).thenReturn(1L);
        when(definition.getName()).thenReturn("Anuidade");
        when(definitions.findByIds(List.of(1L))).thenReturn(List.of(definition));
        authorize(false);
        var hidden = presentation.present(event);
        assertThat(hidden.chargeDefinitionName()).isEqualTo("Anuidade");
        assertThat(hidden.eventTitle()).isNull();
        verifyNoInteractions(events);
        clearInvocations(definitions);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user", "n/a", List.of(new SimpleGrantedAuthority("PUBLICATIONS_EVENT_READ_ADMIN"))));
        when(events.findByIds(List.of(10L))).thenReturn(List.of(
                new EventPresentationQuery.Details(10L, "Passeio", Instant.EPOCH)));
        var page = presentation.present(new PageImpl<>(List.of(event, event,
                assignment(2, ChargeAudienceType.ALL_MEMBERS, null))));
        assertThat(page.getContent()).extracting("eventTitle").containsExactly("Passeio", "Passeio", null);
        verify(definitions).findByIds(List.of(1L));
        verify(events).findByIds(List.of(10L));
    }

    private static ChargeAssignmentResult userAssignment(long id, long userId) {
        return new ChargeAssignmentResult(id, 1L, ChargeAudienceType.USER, userId, null, null, true, Instant.EPOCH, null);
    }

    private static UserDetails user(long id, String name, boolean active) {
        return new UserDetails(id, name, null, null, null, null, null, null, active, null, null, null);
    }
}
