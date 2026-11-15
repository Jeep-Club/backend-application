package com.jeepclub.backend.dependents.core.application.query;

import com.jeepclub.backend.dependents.core.application.exception.InvalidDependentSearchException;
import com.jeepclub.backend.dependents.core.domain.enums.DependentStatus;
import com.jeepclub.backend.dependents.core.domain.enums.RelationshipType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

public record AdminDependentFilter(
        String q,
        String name,
        String cpf,
        List<Long> ids,
        Long userId,
        List<Long> userIds,
        RelationshipType relationshipType,
        DependentStatus status
) {
    public static final int MAX_IDS = 200;
    private static final Pattern NUMERIC_FORMATTED_QUERY = Pattern.compile("^[\\d\\s.\\-()+/]+$");
    private static final Pattern CPF_FILTER_PATTERN = Pattern.compile("^[\\d\\s.\\-]+$");

    public AdminDependentFilter(
            String q,
            String name,
            String cpf,
            Long userId,
            RelationshipType relationshipType,
            DependentStatus status
    ) {
        this(q, name, cpf, null, userId, null, relationshipType, status);
    }

    public AdminDependentFilter {
        q = trim(q);
        name = trim(name);
        cpf = normalizeCpfFilter(cpf);
        if (userId != null && userId <= 0) {
            throw new InvalidDependentSearchException("User ID filter must be positive.");
        }
        if (userId != null && userIds != null) {
            throw new InvalidDependentSearchException("Specify either userId or userIds, not both.");
        }
        ids = normalizeIdList(ids, "ids");
        userIds = normalizeIdList(userIds, "userIds");
    }

    public List<Long> effectiveUserIds() {
        if (userIds != null) {
            return userIds;
        }
        if (userId != null) {
            return List.of(userId);
        }
        return null;
    }

    public static List<Long> normalizeIdList(List<Long> raw, String paramName) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            throw new InvalidDependentSearchException("Filter " + paramName + " must contain at least one ID when provided.");
        }
        if (raw.size() > MAX_IDS) {
            throw new InvalidDependentSearchException("Filter " + paramName + " exceeds maximum of " + MAX_IDS + " IDs.");
        }
        LinkedHashSet<Long> deduplicated = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id == null || id <= 0) {
                throw new InvalidDependentSearchException("Filter " + paramName + " must contain only positive IDs.");
            }
            deduplicated.add(id);
        }
        return List.copyOf(deduplicated);
    }

    public static String extractNumericQueryIfValid(String value) {
        String text = trim(value);
        if (text == null || !NUMERIC_FORMATTED_QUERY.matcher(text).matches()) {
            return null;
        }
        String digits = text.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    public static String normalizeCpfFilter(String value) {
        String text = trim(value);
        if (text == null) {
            return null;
        }
        if (!CPF_FILTER_PATTERN.matcher(text).matches()) {
            throw new InvalidDependentSearchException("CPF filter must contain only digits and formatting characters.");
        }
        String digits = text.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            throw new InvalidDependentSearchException("CPF filter must contain at least one digit.");
        }
        return digits;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
