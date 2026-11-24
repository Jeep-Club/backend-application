package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.core.application.result.MemberPaymentChargeContext;
import com.jeepclub.backend.billing.core.application.result.MemberRefundResult;
import com.jeepclub.backend.billing.core.application.service.memberpayment.AdminMemberPaymentService;
import com.jeepclub.backend.billing.core.domain.enums.refund.MemberRefundStatus;
import com.jeepclub.backend.billing.core.domain.enums.refund.RefundReason;
import com.jeepclub.backend.memberships.api.module.MemberRegistrationQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static com.jeepclub.backend.billing.support.BillingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminMemberRefundPresentationTest {
    private final AdminMemberPaymentService payments = mock(AdminMemberPaymentService.class);
    private final BillingUserNamePresentation users = mock(BillingUserNamePresentation.class);
    private final MemberRegistrationQuery members = mock(MemberRegistrationQuery.class);
    private final AdminMemberRefundPresentation presentation = new AdminMemberRefundPresentation(payments, users, members);

    @AfterEach void resetSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test void preservePageAndIgnoreAnUnrelatedChargeWithoutExtraLookups() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, List.of(new SimpleGrantedAuthority("MEMBERSHIP_MEMBER_READ"))));
        var rows = List.of(refund(8L, 2L, 4L), refund(7L, 2L, 4L), refund(6L, 3L, 4L));
        var page = new PageImpl<>(rows, PageRequest.of(0, 3), 12);
        when(payments.chargeContexts(List.of(2L, 3L))).thenReturn(Map.of(
                2L, new MemberPaymentChargeContext(10L, 5L, 4L, "Historic fee", "2026", DUE),
                3L, new MemberPaymentChargeContext(11L, 6L, 4L, "Unrelated fee", "wrong", DUE)));
        when(users.names(List.of(10L))).thenReturn(Map.of(10L, "Holder"));
        when(members.currentNumbers(List.of(10L))).thenReturn(Map.of(10L, "00042"));

        var result = presentation.summaries(page);

        assertThat(result.getTotalElements()).isEqualTo(12);
        assertThat(result.getContent()).extracting(row -> row.id()).containsExactly(8L, 7L, 6L);
        assertThat(result.getContent().get(0).chargeDefinitionNameSnapshot()).isEqualTo("Historic fee");
        assertThat(result.getContent().get(1).membershipNumber()).isEqualTo("00042");
        assertThat(result.getContent().get(2).chargeDefinitionId()).isNull();
        assertThat(result.getContent().get(2).chargeCycleCode()).isNull();
        verify(payments).chargeContexts(List.of(2L, 3L));
        verify(users).names(List.of(10L));
        verify(members).currentNumbers(List.of(10L));
        verifyNoMoreInteractions(payments, users, members);
    }

    @Test void emptyPageDoesNotResolveFinancialContextOrMembership() {
        assertThat(presentation.summaries(Page.empty(PageRequest.of(2, 3))).getContent()).isEmpty();
        verifyNoInteractions(payments, members);
    }

    @Test void separatesHolderFromAllSixActorsAndKeepsMissingLabelsNull() {
        var refund = new MemberRefundResult(8L, 2L, 1L, 4L, 10L, AMOUNT,
                RefundReason.MEMBER_REQUEST, MemberRefundStatus.REFUNDED, NOW, NOW, 20L,
                NOW, 30L, NOW, 40L, NOW, 50L, null, NOW, 60L, NOW, 70L, NOW, null);
        when(payments.chargeContexts(List.of(2L))).thenReturn(Map.of(
                2L, new MemberPaymentChargeContext(10L, 5L, 4L, "Historic fee", "2026", DUE)));
        var ids = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L);
        when(users.names(ids)).thenReturn(Map.of(10L, "Holder", 20L, "Creator", 30L, "Requester",
                40L, "Approver", 50L, "Reviewer", 60L, "Payer"));

        var response = presentation.detail(refund);

        assertThat(response.userName()).isEqualTo("Holder");
        assertThat(response.createdByUserName()).isEqualTo("Creator");
        assertThat(response.requestedByUserName()).isEqualTo("Requester");
        assertThat(response.approvedByUserName()).isEqualTo("Approver");
        assertThat(response.rejectedByUserName()).isEqualTo("Reviewer");
        assertThat(response.refundedByUserName()).isEqualTo("Payer");
        assertThat(response.canceledByUserId()).isEqualTo(70L);
        assertThat(response.canceledByUserName()).isNull();
        verify(users).names(ids);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(members);
    }

    private static MemberRefundResult refund(long id, long chargeId, long cycleId) {
        return new MemberRefundResult(id, chargeId, 1L, cycleId, 10L, AMOUNT,
                RefundReason.MEMBER_REQUEST, MemberRefundStatus.REQUESTED, null, null, null,
                NOW, 10L, null, null, null, null, null, null, null, null, null, NOW, null);
    }
}
