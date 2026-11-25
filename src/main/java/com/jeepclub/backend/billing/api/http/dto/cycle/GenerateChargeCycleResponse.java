package com.jeepclub.backend.billing.api.http.dto.cycle;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resposta da geração de ciclo de cobrança.")
public record GenerateChargeCycleResponse(

        @Schema(description = "Ciclo de cobrança gerado.")
        ChargeCycleResponse chargeCycle,

        @Schema(description = "Quantidade de cobranças de membro criadas.", example = "35")
        int createdMemberCharges
) {}
