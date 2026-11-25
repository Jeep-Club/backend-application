package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.api.http.dto.cycle.ChargeCycleResponse;
import com.jeepclub.backend.billing.api.http.dto.cycle.GenerateChargeCycleResponse;
import com.jeepclub.backend.billing.core.application.result.cycle.ChargeCycleResult;
import com.jeepclub.backend.billing.core.application.result.cycle.GenerateChargeCycleResult;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminChargeCyclePresentation {
    private final BillingUserNamePresentation users;

    ChargeCycleResponse detail(ChargeCycleResult cycle) {
        var ids = Stream.of(cycle.generatedByUserId(), cycle.canceledByUserId(),
                cycle.finishedByUserId(), cycle.archivedByUserId()).toList();
        return ChargeCycleResponse.from(cycle, users.names(ids));
    }

    // Compose the response before commit so a technical lookup failure rolls back the command.
    @Transactional
    public ChargeCycleResponse transition(Supplier<ChargeCycleResult> command) {
        return detail(command.get());
    }

    @Transactional
    public GenerateChargeCycleResponse generate(Supplier<GenerateChargeCycleResult> command) {
        var result = command.get();
        return new GenerateChargeCycleResponse(detail(result.chargeCycle()), result.createdMemberCharges());
    }
}
