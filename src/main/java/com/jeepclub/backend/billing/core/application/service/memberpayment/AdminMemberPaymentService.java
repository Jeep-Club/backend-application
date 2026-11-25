package com.jeepclub.backend.billing.core.application.service.memberpayment;

import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeNotFoundException;
import com.jeepclub.backend.billing.core.application.exception.payment.MemberPaymentNotFoundException;
import com.jeepclub.backend.billing.core.application.result.MemberPaymentChargeContext;
import com.jeepclub.backend.billing.core.application.result.MemberPaymentResult;
import com.jeepclub.backend.billing.core.domain.enums.payment.MemberPaymentStatus;
import com.jeepclub.backend.billing.core.domain.exception.payment.InvalidMemberPaymentStateException;
import com.jeepclub.backend.billing.core.domain.model.ChargeCycle;
import com.jeepclub.backend.billing.core.domain.model.MemberCharge;
import com.jeepclub.backend.billing.core.domain.model.MemberPayment;
import com.jeepclub.backend.billing.core.repository.MemberChargeRepository;
import com.jeepclub.backend.billing.core.repository.MemberPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminMemberPaymentService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final MemberChargeRepository memberChargeRepository;
    private final Clock clock;
    private final com.jeepclub.backend.billing.core.repository.ChargeCycleRepository cycles;
    private final com.jeepclub.backend.billing.core.application.service.memberrefund.AdminMemberRefundService refunds;

    @Transactional(readOnly = true)
    public Page<MemberPaymentResult> findAll(MemberPaymentStatus status, Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable cannot be null");
        if (status != null) {
            return memberPaymentRepository.findByStatus(status, pageable)
                    .map(MemberPaymentResult::from);
        }
        return memberPaymentRepository.findAll(pageable).map(MemberPaymentResult::from);
    }

    @Transactional(readOnly = true)
    public MemberPaymentResult findById(Long id) {
        return MemberPaymentResult.from(findMemberPaymentOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Map<Long, MemberPaymentChargeContext> chargeContexts(Collection<Long> chargeIds) {
        if (chargeIds.isEmpty()) return Map.of();
        var charges = memberChargeRepository.findByIds(chargeIds);
        var cycleIds = charges.stream().map(MemberCharge::getChargeCycleId).distinct().toList();
        if (cycleIds.isEmpty()) return Map.of();
        var cyclesById = cycles.findByIds(cycleIds).stream()
                .collect(Collectors.toMap(ChargeCycle::getId, cycle -> cycle));
        return charges.stream().collect(Collectors.toMap(MemberCharge::getId, charge -> {
            var cycle = cyclesById.get(charge.getChargeCycleId());
            // Retain the charge's own IDs and date even when a legacy cycle cannot be resolved.
            if (cycle != null && !Objects.equals(cycle.getChargeDefinitionId(), charge.getChargeDefinitionId())) {
                cycle = null;
            }
            return new MemberPaymentChargeContext(charge.getUserId(), charge.getChargeDefinitionId(),
                    charge.getChargeCycleId(), cycle == null ? null : cycle.getChargeDefinitionNameSnapshot(),
                    cycle == null ? null : cycle.getCode(), charge.getDueDate());
        }));
    }

    @Transactional
    public MemberPaymentResult confirm(Long paymentId, Long confirmedByUserId) {
        Objects.requireNonNull(confirmedByUserId, "confirmedByUserId cannot be null");
        var cycleId = memberPaymentRepository.findChargeCycleIdById(paymentId)
                .orElseThrow(() -> new MemberPaymentNotFoundException("Member payment not found."));
        var cycle = cycles.findByIdForUpdate(cycleId).orElseThrow();
        MemberPayment memberPayment = findMemberPaymentForUpdateOrThrow(paymentId);
        MemberCharge memberCharge = findMemberChargeForUpdateOrThrow(memberPayment.getMemberChargeId());

        boolean canceledCycle = cycle.getCanceledAt() != null;
        if (!canceledCycle) ensureChargeCanBeMarkedAsPaid(memberCharge);
        ensurePaymentAmountStillMatchesCharge(memberPayment, memberCharge);
        // Lock waits must not consume the late-refund window before confirmation occurs.
        Instant now = Instant.now(clock);
        memberPayment.confirm(confirmedByUserId, now);
        if (!canceledCycle) memberCharge.markAsPaid(memberPayment.getPaidAt(), now);

        memberChargeRepository.save(memberCharge);
        var saved = memberPaymentRepository.save(memberPayment);
        if (canceledCycle) refunds.ensureLateEligibility(memberCharge, saved, confirmedByUserId, cycle.getCanceledAt());
        return MemberPaymentResult.from(saved);
    }

    @Transactional
    public MemberPaymentResult reject(Long paymentId, Long rejectedByUserId, String rejectionReason) {
        Objects.requireNonNull(rejectedByUserId, "rejectedByUserId cannot be null");
        MemberPayment memberPayment = findMemberPaymentForUpdateOrThrow(paymentId);
        memberPayment.reject(rejectedByUserId, rejectionReason, Instant.now(clock));
        return MemberPaymentResult.from(memberPaymentRepository.save(memberPayment));
    }

    private static void ensureChargeCanBeMarkedAsPaid(MemberCharge charge) {
        Objects.requireNonNull(charge, "memberCharge cannot be null");
        if (!charge.isPending()) {
            throw new InvalidMemberPaymentStateException("Only pending member charges can be paid.");
        }
    }

    private static void ensurePaymentAmountStillMatchesCharge(MemberPayment payment, MemberCharge charge) {
        Objects.requireNonNull(payment, "memberPayment cannot be null");
        Objects.requireNonNull(charge, "memberCharge cannot be null");
        if (payment.getAmount().compareTo(charge.getFinalAmount()) != 0) {
            throw new InvalidMemberPaymentStateException(
                    "Payment amount no longer matches member charge final amount."
            );
        }
    }

    private MemberPayment findMemberPaymentOrThrow(Long id) {
        Objects.requireNonNull(id, "id cannot be null");
        return memberPaymentRepository.findById(id)
                .orElseThrow(() -> new MemberPaymentNotFoundException("Member payment not found."));
    }

    private MemberPayment findMemberPaymentForUpdateOrThrow(Long id) {
        Objects.requireNonNull(id, "id cannot be null");
        return memberPaymentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new MemberPaymentNotFoundException("Member payment not found."));
    }

    private MemberCharge findMemberChargeForUpdateOrThrow(Long id) {
        Objects.requireNonNull(id, "id cannot be null");
        return memberChargeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new MemberChargeNotFoundException("Member charge not found."));
    }
}
