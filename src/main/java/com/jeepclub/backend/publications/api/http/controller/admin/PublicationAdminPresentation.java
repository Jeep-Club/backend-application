package com.jeepclub.backend.publications.api.http.controller.admin;

import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.publications.api.http.dto.AdminServicePublicationChangeRequestResponseDTO;
import com.jeepclub.backend.publications.api.http.dto.AdminServicePublicationRequestResponseDTO;
import com.jeepclub.backend.publications.api.http.dto.NoticeResponseDTO;
import com.jeepclub.backend.publications.api.http.dto.ServicePublicationResponseDTO;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.model.Notice;
import com.jeepclub.backend.publications.core.domain.model.ServicePublication;
import com.jeepclub.backend.publications.core.domain.model.ServicePublicationChangeRequest;
import com.jeepclub.backend.publications.core.domain.model.ServicePublicationRequest;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import java.util.ArrayList;
import java.util.Collection;
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
public class PublicationAdminPresentation {
    private final UserQuery users;
    private final PublicationRepository publications;

    public NoticeResponseDTO notice(Notice notice) {
        String authorName = userNames(List.of(notice.getAuthorUserId())).get(notice.getAuthorUserId());
        return NoticeResponseDTO.from(notice, authorName);
    }

    public Page<NoticeResponseDTO> notices(Page<Notice> page) {
        if (page.isEmpty()) {
            return page.map(NoticeResponseDTO::from);
        }
        Map<Long, String> names = userNames(page.getContent().stream().map(Notice::getAuthorUserId).toList());
        return page.map(item -> NoticeResponseDTO.from(item, names.get(item.getAuthorUserId())));
    }

    public ServicePublicationResponseDTO service(ServicePublication service) {
        String authorName = userNames(List.of(service.getAuthorUserId())).get(service.getAuthorUserId());
        return ServicePublicationResponseDTO.from(service, authorName);
    }

    public Page<ServicePublicationResponseDTO> services(Page<ServicePublication> page) {
        if (page.isEmpty()) {
            return page.map(ServicePublicationResponseDTO::from);
        }
        Map<Long, String> names = userNames(page.getContent().stream().map(ServicePublication::getAuthorUserId).toList());
        return page.map(item -> ServicePublicationResponseDTO.from(item, names.get(item.getAuthorUserId())));
    }

    public AdminServicePublicationRequestResponseDTO request(ServicePublicationRequest request) {
        List<Long> userIds = new ArrayList<>(2);
        userIds.add(request.getRequestedByUserId());
        if (request.getReviewedByUserId() != null) {
            userIds.add(request.getReviewedByUserId());
        }
        Map<Long, String> names = userNames(userIds);
        String createdTitle = request.getCreatedPublicationId() == null
                ? null
                : serviceTitles(List.of(request.getCreatedPublicationId())).get(request.getCreatedPublicationId());
        return AdminServicePublicationRequestResponseDTO.from(
                request,
                names.get(request.getRequestedByUserId()),
                request.getReviewedByUserId() == null ? null : names.get(request.getReviewedByUserId()),
                createdTitle
        );
    }

    public Page<AdminServicePublicationRequestResponseDTO> requests(Page<ServicePublicationRequest> page) {
        if (page.isEmpty()) {
            return page.map(AdminServicePublicationRequestResponseDTO::from);
        }
        List<Long> userIds = new ArrayList<>(page.getContent().size() * 2);
        List<Long> publicationIds = new ArrayList<>(page.getContent().size());
        for (ServicePublicationRequest item : page.getContent()) {
            userIds.add(item.getRequestedByUserId());
            if (item.getReviewedByUserId() != null) {
                userIds.add(item.getReviewedByUserId());
            }
            if (item.getCreatedPublicationId() != null) {
                publicationIds.add(item.getCreatedPublicationId());
            }
        }
        Map<Long, String> names = userNames(userIds);
        Map<Long, String> titles = serviceTitles(publicationIds);
        return page.map(item -> AdminServicePublicationRequestResponseDTO.from(
                item,
                names.get(item.getRequestedByUserId()),
                item.getReviewedByUserId() == null ? null : names.get(item.getReviewedByUserId()),
                item.getCreatedPublicationId() == null ? null : titles.get(item.getCreatedPublicationId())
        ));
    }

    public AdminServicePublicationChangeRequestResponseDTO change(ServicePublicationChangeRequest change) {
        List<Long> userIds = new ArrayList<>(2);
        userIds.add(change.getRequestedByUserId());
        if (change.getReviewedByUserId() != null) {
            userIds.add(change.getReviewedByUserId());
        }
        Map<Long, String> names = userNames(userIds);
        String serviceTitle = change.getServicePublicationId() == null
                ? null
                : serviceTitles(List.of(change.getServicePublicationId())).get(change.getServicePublicationId());
        return AdminServicePublicationChangeRequestResponseDTO.from(
                change,
                serviceTitle,
                names.get(change.getRequestedByUserId()),
                change.getReviewedByUserId() == null ? null : names.get(change.getReviewedByUserId())
        );
    }

    public Page<AdminServicePublicationChangeRequestResponseDTO> changes(Page<ServicePublicationChangeRequest> page) {
        if (page.isEmpty()) {
            return page.map(AdminServicePublicationChangeRequestResponseDTO::from);
        }
        List<Long> userIds = new ArrayList<>(page.getContent().size() * 2);
        List<Long> publicationIds = new ArrayList<>(page.getContent().size());
        for (ServicePublicationChangeRequest item : page.getContent()) {
            userIds.add(item.getRequestedByUserId());
            if (item.getReviewedByUserId() != null) {
                userIds.add(item.getReviewedByUserId());
            }
            if (item.getServicePublicationId() != null) {
                publicationIds.add(item.getServicePublicationId());
            }
        }
        Map<Long, String> names = userNames(userIds);
        Map<Long, String> titles = serviceTitles(publicationIds);
        return page.map(item -> AdminServicePublicationChangeRequestResponseDTO.from(
                item,
                item.getServicePublicationId() == null ? null : titles.get(item.getServicePublicationId()),
                names.get(item.getRequestedByUserId()),
                item.getReviewedByUserId() == null ? null : names.get(item.getReviewedByUserId())
        ));
    }

    Map<Long, String> userNames(Collection<Long> ids) {
        if (!hasAuthority("IDENTITY_USER_READ")) {
            return Map.of();
        }
        List<Long> distinct = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return users.findByIds(distinct).stream()
                .filter(UserDetails::administrativelyActive)
                .filter(user -> user.status() == UserStatus.ACTIVE && user.archivedAt() == null)
                .filter(user -> user.name() != null && !user.name().isBlank())
                .collect(Collectors.toMap(UserDetails::id, UserDetails::name, (a, b) -> a));
    }

    Map<Long, String> serviceTitles(Collection<Long> ids) {
        List<Long> distinct = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        boolean canReadArchivedService = hasAuthority("PUBLICATIONS_SERVICE_ADMIN_READ");
        return publications.findServicesByIds(distinct).stream()
                .filter(service -> service.getStatus() == PublicationStatus.PUBLISHED
                        || (service.getStatus() == PublicationStatus.ARCHIVED && canReadArchivedService))
                .filter(service -> service.getTitle() != null && !service.getTitle().isBlank())
                .collect(Collectors.toMap(ServicePublication::getId, ServicePublication::getTitle, (a, b) -> a));
    }

    static boolean hasAuthority(String authority) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
