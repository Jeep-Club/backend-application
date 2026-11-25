package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.api.http.dto.payment.AdminMemberPaymentResponse;
import com.jeepclub.backend.billing.api.http.dto.payment.MemberPaymentSummaryResponse;
import com.jeepclub.backend.billing.core.application.result.MemberPaymentChargeContext;
import com.jeepclub.backend.billing.core.application.result.MemberPaymentResult;
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
class AdminMemberPaymentPresentation {
    private static final MemberPaymentChargeContext MISSING_CHARGE =
            new MemberPaymentChargeContext(null, null, null, null, null, null);

    private final AdminMemberPaymentService payments;
    private final BillingUserNamePresentation userNames;
    private final MemberRegistrationQuery members;

    Page<MemberPaymentSummaryResponse> summaries(Page<MemberPaymentResult> page) {
        if (!page.hasContent()) return page.map(payment ->
                MemberPaymentSummaryResponse.from(payment, MISSING_CHARGE, null, null));

        var chargeIds = page.getContent().stream().map(MemberPaymentResult::memberChargeId).distinct().toList();
        var contexts = payments.chargeContexts(chargeIds);
        var userIds = contexts.values().stream().map(MemberPaymentChargeContext::userId).distinct().toList();
        var names = userNames.names(userIds);
        var numbers = membershipNumbers(userIds);
        return page.map(payment -> {
            var charge = contexts.getOrDefault(payment.memberChargeId(), MISSING_CHARGE);
            return MemberPaymentSummaryResponse.from(payment, charge,
                    label(names, charge.userId()), label(numbers, charge.userId()));
        });
    }

    AdminMemberPaymentResponse detail(MemberPaymentResult payment) {
        var charge = payments.chargeContexts(List.of(payment.memberChargeId()))
                .getOrDefault(payment.memberChargeId(), MISSING_CHARGE);
        var userIds = Stream.of(charge.userId(), payment.confirmedByUserId(), payment.rejectedByUserId())
                .filter(Objects::nonNull).distinct().toList();
        var names = userNames.names(userIds);
        var numbers = membershipNumbers(charge.userId() == null ? List.of() : List.of(charge.userId()));
        return AdminMemberPaymentResponse.from(payment, charge,
                label(names, charge.userId()), label(numbers, charge.userId()),
                label(names, payment.confirmedByUserId()), label(names, payment.rejectedByUserId()));
    }

    private Map<Long, String> membershipNumbers(List<Long> ids) {
        return ids.isEmpty() || !BillingUserNamePresentation.hasAuthority("MEMBERSHIP_MEMBER_READ")
                ? Map.of() : BillingUserNamePresentation.lookup(() -> members.currentNumbers(ids));
    }

    private static String label(Map<Long, String> labels, Long id) {
        return id == null ? null : labels.get(id);
    }
}
