package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.api.http.dto.assignment.ChargeAssignmentResponse;
import com.jeepclub.backend.billing.core.application.result.ChargeAssignmentResult;
import com.jeepclub.backend.billing.core.domain.enums.assignment.ChargeAudienceType;
import com.jeepclub.backend.billing.core.domain.model.ChargeDefinition;
import com.jeepclub.backend.billing.core.repository.ChargeDefinitionRepository;
import com.jeepclub.backend.iam.authorization.api.module.RolePresentationQuery;
import com.jeepclub.backend.publications.api.module.EventPresentationQuery;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChargeAssignmentPresentation {
    private final RolePresentationQuery roles;
    private final BillingUserNamePresentation users;
    private final ChargeDefinitionRepository definitions;
    private final EventPresentationQuery events;

    public static boolean canReadRoles() {
        return BillingUserNamePresentation.hasAuthority("AUTHORIZATION_ROLE_READ");
    }

    public Page<ChargeAssignmentResponse> present(Page<ChargeAssignmentResult> results) {
        return results.map(responses(results.getContent()));
    }

    public ChargeAssignmentResponse present(ChargeAssignmentResult result) {
        return responses(List.of(result)).apply(result);
    }

    private Function<ChargeAssignmentResult, ChargeAssignmentResponse> responses(List<ChargeAssignmentResult> results) {
        boolean allowed = canReadRoles();
        var roleNames = roleNames(results, allowed);
        var userNames = userNames(results);
        var definitionIds = results.stream().map(ChargeAssignmentResult::chargeDefinitionId).distinct().toList();
        Map<Long, String> definitionNames = definitionIds.isEmpty() ? Map.of() : definitions.findByIds(definitionIds)
                .stream().collect(Collectors.toMap(ChargeDefinition::getId, ChargeDefinition::getName));
        var eventTitles = eventTitles(results);
        return result -> ChargeAssignmentResponse.from(result, allowed,
                result.roleId() == null ? null : roleNames.get(result.roleId()),
                result.userId() == null ? null : userNames.get(result.userId()),
                definitionNames.get(result.chargeDefinitionId()),
                result.eventId() == null ? null : eventTitles.get(result.eventId()));
    }

    private Map<Long, String> eventTitles(List<ChargeAssignmentResult> results) {
        if (!BillingUserNamePresentation.hasAuthority("PUBLICATIONS_EVENT_READ_ADMIN")) return Map.of();
        var ids = results.stream().filter(r -> r.audienceType() == ChargeAudienceType.EVENT_PARTICIPANTS)
                .map(ChargeAssignmentResult::eventId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return BillingUserNamePresentation.lookup(() -> events.findByIds(ids)).stream()
                .filter(event -> event.title() != null && !event.title().isBlank())
                .collect(Collectors.toMap(EventPresentationQuery.Details::id, EventPresentationQuery.Details::title, (a, b) -> a));
    }

    private Map<Long, String> userNames(List<ChargeAssignmentResult> results) {
        return users.names(results.stream().filter(r -> r.audienceType() == ChargeAudienceType.USER)
                .map(ChargeAssignmentResult::userId).toList());
    }

    private Map<Long, String> roleNames(List<ChargeAssignmentResult> results, boolean allowed) {
        if (!allowed) return Map.of();
        var ids = results.stream().filter(r -> r.audienceType() == ChargeAudienceType.ROLE)
                .map(ChargeAssignmentResult::roleId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return roles.findByIds(ids).stream().filter(r -> r.id() != null && r.name() != null)
                .collect(Collectors.toMap(RolePresentationQuery.Details::id, RolePresentationQuery.Details::name, (a, b) -> a));
    }
}
