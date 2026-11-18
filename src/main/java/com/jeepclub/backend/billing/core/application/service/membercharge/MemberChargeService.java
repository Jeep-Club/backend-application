package com.jeepclub.backend.billing.core.application.service.membercharge;

import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeAccessDeniedException;
import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeNotFoundException;
import com.jeepclub.backend.billing.core.application.result.charge.MemberChargeResult;
import com.jeepclub.backend.billing.core.application.result.charge.MemberChargeCycleResult;
import com.jeepclub.backend.billing.core.application.result.charge.MemberChargeSelfServiceResult;
import com.jeepclub.backend.billing.core.domain.enums.charge.MemberChargeStatus;
import com.jeepclub.backend.billing.core.domain.model.MemberCharge;
import com.jeepclub.backend.billing.core.domain.model.ChargeCycle;
import com.jeepclub.backend.billing.core.repository.ChargeCycleRepository;
import com.jeepclub.backend.billing.core.repository.MemberChargeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MemberChargeService {

    private final MemberChargeRepository memberChargeRepository;
    private final ChargeCycleRepository chargeCycleRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<MemberChargeSelfServiceResult> findMine(
            Long authenticatedUserId,
            MemberChargeStatus status,
            Pageable pageable
    ) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId cannot be null");
        Objects.requireNonNull(pageable, "pageable cannot be null");

        Page<MemberCharge> page = status != null
                ? memberChargeRepository.findByUserIdAndStatus(authenticatedUserId, status, pageable)
                : memberChargeRepository.findByUserId(authenticatedUserId, pageable);
        return MemberChargeCyclePresentation.enrich(page.map(this::toResult), chargeCycleRepository)
                .map(MemberChargeService::selfServiceResult);
    }

    @Transactional(readOnly = true)
    public MemberChargeSelfServiceResult findMineById(
            Long authenticatedUserId,
            Long memberChargeId
    ) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId cannot be null");

        MemberCharge memberCharge = findMemberChargeOrThrow(memberChargeId);

        if (!memberCharge.getUserId().equals(authenticatedUserId)) {
            throw new MemberChargeAccessDeniedException(
                    "Member charge does not belong to authenticated user."
            );
        }

        ChargeCycle cycle = chargeCycleRepository.findById(memberCharge.getChargeCycleId()).orElse(null);
        return selfServiceResult(MemberChargeCyclePresentation.enrich(toResult(memberCharge), cycle));
    }

    private static MemberChargeSelfServiceResult selfServiceResult(MemberChargeCycleResult result) {
        return new MemberChargeSelfServiceResult(result.charge(), result.chargeDefinitionNameSnapshot(), result.chargeCycleCode());
    }

    private MemberChargeResult toResult(MemberCharge memberCharge) {
        return MemberChargeResult.from(memberCharge, LocalDate.now(clock));
    }

    private MemberCharge findMemberChargeOrThrow(Long id) {
        Objects.requireNonNull(id, "id cannot be null");

        return memberChargeRepository.findById(id)
                .orElseThrow(() -> new MemberChargeNotFoundException(
                        "Member charge not found."
                ));
    }
}
