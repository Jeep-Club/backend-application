package com.jeepclub.backend.publications.core.application.service;

import com.jeepclub.backend.billing.api.module.*;
import com.jeepclub.backend.dependents.api.module.DependentsQuery;
import com.jeepclub.backend.health.api.module.medicalprofile.*;
import com.jeepclub.backend.platform.logging.SystemLogService;
import com.jeepclub.backend.platform.storage.image.ImageMediaService;
import com.jeepclub.backend.publications.core.application.service.internal.EventOperations;
import com.jeepclub.backend.publications.core.domain.model.EventRegistration;
import com.jeepclub.backend.publications.core.repository.*;
import com.jeepclub.backend.vehicles.api.module.EventVehicleQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminEventHealthAuditTest {
    @Mock EventOperations events;
    @Mock PublicationRepository publications;
    @Mock EventOperationsRepository operations;
    @Mock ImageMediaService media;
    @Mock EventBillingCommand billing;
    @Mock EventFinancialQuery financial;
    @Mock EventChargeCatalogQuery catalog;
    @Mock EventVehicleQuery vehicles;
    @Mock EmergencyMedicalProfileQuery health;
    @Mock DependentsQuery dependents;
    @Mock SystemLogService audit;
    @InjectMocks AdminEventService service;

    @Test void auditFailurePreventsReturningTheClinicalProfile() {
        var now = Instant.parse("2026-10-01T12:00:00Z");
        var registration = new EventRegistration(1L, 2L, 3L, EventRegistration.Status.CONFIRMED,
            List.of(), now, now, null);
        var profile = new EmergencyMedicalProfileQuery.Profile("O_POSITIVE", "private", null, null, null, null, null, null, null, null, null);
        when(events.refreshed(2L)).thenReturn(List.of(registration));
        when(events.now()).thenReturn(now);
        when(health.find(MedicalProfileOwner.USER, 3L)).thenReturn(Optional.of(profile));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> service.health(2L, MedicalProfileOwner.USER, 3L, 4L))
            .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
        verify(audit).recordRequired(any());
    }

    @Test void deniedHealthAccessIsAuditedWithoutClinicalContent() {
        var now = Instant.parse("2026-10-01T12:00:00Z");
        var registration = new EventRegistration(1L, 2L, 3L, EventRegistration.Status.CONFIRMED,
            List.of(), now, now, null);
        when(events.refreshed(2L)).thenReturn(List.of(registration));
        when(events.now()).thenReturn(now);
        when(health.find(MedicalProfileOwner.USER, 3L)).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.health(2L, MedicalProfileOwner.USER, 3L, 4L))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var recorded = org.mockito.ArgumentCaptor.forClass(com.jeepclub.backend.platform.logging.SystemLogEvent.class);
        verify(audit).recordRequired(recorded.capture());
        assertThat(recorded.getValue().status()).isEqualTo(403);
        assertThat(recorded.getValue().outcome()).isEqualTo(com.jeepclub.backend.platform.logging.SystemLogOutcome.CLIENT_ERROR);
        assertThat(recorded.getValue().path()).isEqualTo("/admin/events/2/health/USER/3");
    }
}
