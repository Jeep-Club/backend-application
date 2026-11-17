package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BillingUserNamePresentation {
    private final UserQuery users;

    Map<Long, String> names(Collection<Long> ids) {
        if (!hasAuthority("IDENTITY_USER_READ")) return Map.of();
        var distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        return users.findByIds(distinct).stream()
                .filter(UserDetails::administrativelyActive)
                .filter(user -> user.status() == UserStatus.ACTIVE && user.archivedAt() == null)
                .filter(user -> user.name() != null && !user.name().isBlank())
                .collect(Collectors.toMap(UserDetails::id, UserDetails::name));
    }

    static boolean hasAuthority(String authority) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
