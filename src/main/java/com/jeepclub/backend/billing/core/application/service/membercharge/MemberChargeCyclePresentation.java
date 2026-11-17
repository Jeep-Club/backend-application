package com.jeepclub.backend.billing.core.application.service.membercharge;

import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeCycleIntegrityException;
import com.jeepclub.backend.billing.core.application.result.charge.MemberChargeResult;
import com.jeepclub.backend.billing.core.application.result.charge.MemberChargeCycleResult;
import com.jeepclub.backend.billing.core.domain.model.ChargeCycle;
import com.jeepclub.backend.billing.core.repository.ChargeCycleRepository;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;

final class MemberChargeCyclePresentation {
    private MemberChargeCyclePresentation() {}

    static Page<MemberChargeCycleResult> enrich(Page<MemberChargeResult> page, ChargeCycleRepository repository) {
        var ids = page.getContent().stream().map(MemberChargeResult::chargeCycleId).distinct().toList();
        Map<Long, ChargeCycle> cycles = ids.isEmpty() ? Map.of() : repository.findByIds(ids).stream()
                .collect(Collectors.toMap(ChargeCycle::getId, cycle -> cycle));
        return page.map(charge -> enrich(charge, cycles.get(charge.chargeCycleId())));
    }

    static MemberChargeCycleResult enrich(MemberChargeResult charge, ChargeCycle cycle) {
        if (cycle == null || !Objects.equals(cycle.getChargeDefinitionId(), charge.chargeDefinitionId())) {
            throw new MemberChargeCycleIntegrityException();
        }
        return new MemberChargeCycleResult(charge, cycle.getChargeDefinitionNameSnapshot(), cycle.getCode());
    }
}
