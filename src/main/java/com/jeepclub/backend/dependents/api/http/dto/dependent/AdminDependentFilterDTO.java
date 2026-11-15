package com.jeepclub.backend.dependents.api.http.dto.dependent;

import com.jeepclub.backend.dependents.core.application.query.AdminDependentFilter;
import com.jeepclub.backend.dependents.core.domain.enums.DependentStatus;
import com.jeepclub.backend.dependents.core.domain.enums.RelationshipType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Filtros administrativos de dependentes combinados com AND. ids e userIds usam OR dentro de cada lista (máximo 200 itens, positivos e deduplicados). q usa OR entre nome do dependente, nome do titular e, quando puramente numérico/formatado, CPF e telefone; texto literal, trim e case-insensitive.")
public record AdminDependentFilterDTO(
        @Size(max = 150)
        @Schema(description = "Busca geral literal por nome do dependente, nome do titular ou CPF/telefone numérico/formatado.")
        String q,

        @Size(max = 150)
        @Schema(description = "Filtro por nome do dependente (substring literal case-insensitive).")
        String name,

        @Size(max = 20)
        @Schema(description = "Filtro por CPF do dependente (com ou sem máscara).")
        String cpf,

        @Schema(description = "Identificadores de dependentes para seleção múltipla (repetível: ids=1&ids=2, máximo 200).")
        List<@Positive Long> ids,

        @Positive
        @Schema(description = "Identificador exato do usuário titular (compatibilidade escalar; não combinar com userIds).")
        Long userId,

        @Schema(description = "Identificadores de usuários titulares para seleção múltipla (repetível: userIds=1&userIds=2, máximo 200; não combinar com userId).")
        List<@Positive Long> userIds,

        @Schema(description = "Tipo de relacionamento com o usuário titular.")
        RelationshipType relationshipType,

        @Schema(description = "Status operacional do dependente (ACTIVE, INACTIVE, ARCHIVED). Omitido retorna todos.")
        DependentStatus status
) {
    public AdminDependentFilter toFilter() {
        return new AdminDependentFilter(q, name, cpf, ids, userId, userIds, relationshipType, status);
    }
}
