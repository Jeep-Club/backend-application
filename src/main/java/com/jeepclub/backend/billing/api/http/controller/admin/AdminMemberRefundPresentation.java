package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.api.http.dto.refund.AdminMemberRefundResponse;
import com.jeepclub.backend.billing.api.http.dto.refund.AdminMemberRefundSummaryResponse;
import com.jeepclub.backend.billing.core.application.result.MemberPaymentChargeContext;
import com.jeepclub.backend.billing.core.application.result.MemberRefundResult;
import com.jeepclub.backend.billing.core.application.service.memberpayment.AdminMemberPaymentService;
import com.jeepclub.backend.memberships.api.module.MemberRegistrationQuery;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AdminMemberRefundPresentation {
    private static final MemberPaymentChargeContext MISSING_CHARGE =
            new MemberPaymentChargeContext(null, null, null, null, null, null);

    private final AdminMemberPaymentService payments;
    private final BillingUserNamePresentation userNames;
    private final MemberRegistrationQuery members;

    Page<AdminMemberRefundSummaryResponse> summaries(Page<MemberRefundResult> page) {
        var rows = page.getContent();
        var chargeIds = rows.stream().map(MemberRefundResult::memberChargeId).distinct().toList();
        var contexts = chargeIds.isEmpty() ? Map.<Long, MemberPaymentChargeContext>of() : payments.chargeContexts(chargeIds);
        var holderIds = rows.stream().map(MemberRefundResult::userId).distinct().toList();
        var names = userNames.names(holderIds);
        var numbers = membershipNumbers(holderIds);
        return page.map(refund -> {
            var charge = relatedCharge(refund, contexts);
            return AdminMemberRefundSummaryResponse.from(refund, label(names, refund.userId()),
                    label(numbers, refund.userId()), charge.chargeDefinitionId(),
                    charge.chargeDefinitionNameSnapshot(), charge.chargeCycleCode());
        });
    }

    AdminMemberRefundResponse detail(MemberRefundResult refund) {
        var charge = relatedCharge(refund, payments.chargeContexts(List.of(refund.memberChargeId())));
        var ids = Stream.of(refund.userId(), refund.createdByUserId(), refund.requestedByUserId(),
                refund.approvedByUserId(), refund.rejectedByUserId(), refund.refundedByUserId(), refund.canceledByUserId())
                .filter(Objects::nonNull).distinct().toList();
        var names = userNames.names(ids);
        var numbers = membershipNumbers(List.of(refund.userId()));
        return AdminMemberRefundResponse.from(refund, label(names, refund.userId()), label(numbers, refund.userId()),
                charge.chargeDefinitionId(), charge.chargeDefinitionNameSnapshot(), charge.chargeCycleCode(),
                label(names, refund.createdByUserId()), label(names, refund.requestedByUserId()),
                label(names, refund.approvedByUserId()), label(names, refund.rejectedByUserId()),
                label(names, refund.refundedByUserId()), label(names, refund.canceledByUserId()));
    }

    private static MemberPaymentChargeContext relatedCharge(MemberRefundResult refund,
                                                          Map<Long, MemberPaymentChargeContext> contexts) {
        var charge = contexts.get(refund.memberChargeId());
        if (charge == null || !Objects.equals(charge.userId(), refund.userId())
                || !Objects.equals(charge.chargeCycleId(), refund.chargeCycleId())) {
            return MISSING_CHARGE;
        }
        return charge;
    }

    private Map<Long, String> membershipNumbers(List<Long> ids) {
        return ids.isEmpty() || !BillingUserNamePresentation.hasAuthority("MEMBERSHIP_MEMBER_READ")
                ? Map.of() : BillingUserNamePresentation.lookup(() -> members.currentNumbers(ids));
    }

    private static String label(Map<Long, String> labels, Long id) {
        return id == null ? null : labels.get(id);
    }
}
