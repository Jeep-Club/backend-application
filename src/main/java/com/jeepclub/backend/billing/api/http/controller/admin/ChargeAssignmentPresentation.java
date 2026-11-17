package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.api.http.dto.assignment.ChargeAssignmentResponse;
import com.jeepclub.backend.billing.core.application.result.ChargeAssignmentResult;
import com.jeepclub.backend.billing.core.domain.enums.assignment.ChargeAudienceType;
import com.jeepclub.backend.iam.authorization.api.module.RolePresentationQuery;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChargeAssignmentPresentation {
    private final RolePresentationQuery roles;
    private final BillingUserNamePresentation users;

    public static boolean canReadRoles() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("AUTHORIZATION_ROLE_READ"));
    }

    public Page<ChargeAssignmentResponse> present(Page<ChargeAssignmentResult> results) {
        boolean allowed = canReadRoles();
        Map<Long, String> names = names(results.getContent(), allowed);
        var userNames = userNames(results.getContent());
        return results.map(result -> response(result, allowed, names, userNames));
    }

    public ChargeAssignmentResponse present(ChargeAssignmentResult result) {
        boolean allowed = canReadRoles();
        return response(result, allowed, names(List.of(result), allowed), Map.of());
    }

    public ChargeAssignmentResponse presentRead(ChargeAssignmentResult result) {
        boolean allowed = canReadRoles();
        var results = List.of(result);
        return response(result, allowed, names(results, allowed), userNames(results));
    }

    private Map<Long, String> userNames(List<ChargeAssignmentResult> results) {
        return users.names(results.stream().filter(r -> r.audienceType() == ChargeAudienceType.USER)
                .map(ChargeAssignmentResult::userId).toList());
    }

    private Map<Long, String> names(List<ChargeAssignmentResult> results, boolean allowed) {
        if (!allowed) return Map.of();
        var ids = results.stream().filter(r -> r.audienceType() == ChargeAudienceType.ROLE)
                .map(ChargeAssignmentResult::roleId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return roles.findByIds(ids).stream().filter(r -> r.id() != null && r.name() != null)
                .collect(Collectors.toMap(RolePresentationQuery.Details::id, RolePresentationQuery.Details::name, (a, b) -> a));
    }

    private ChargeAssignmentResponse response(ChargeAssignmentResult result, boolean allowed, Map<Long, String> names, Map<Long, String> userNames) {
        return ChargeAssignmentResponse.from(result, allowed, result.roleId() == null ? null : names.get(result.roleId()),
                result.userId() == null ? null : userNames.get(result.userId()));
    }
}
