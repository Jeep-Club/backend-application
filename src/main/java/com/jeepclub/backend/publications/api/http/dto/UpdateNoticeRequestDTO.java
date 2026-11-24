package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

@Schema(description = "Edição parcial: campo omitido preserva o atual; null explícito é inválido exceto em validFrom e validUntil (onde null explícito remove o limite). "
        + "Galeria enviada substitui a anterior. ARCHIVED é somente leitura.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateNoticeRequestDTO(
        @Schema(description = "Novo título. Omitir preserva.", maxLength = 200, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 200) String title,
        @Schema(description = "Novo conteúdo. Omitir preserva.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String content,
        @Schema(description = "Nova galeria completa, 1 a 5 imagens. Omitir preserva a galeria anterior.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 5) List<@NotNull @Valid PublicationImageRequestDTO> images,
        @Schema(description = "Nova prioridade editorial. Omitir preserva; null explícito é inválido.",
                allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"}, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        NoticePriority priority,
        @Schema(description = "Novo início inclusivo de validade. Omitir preserva; null explícito remove o limite inicial.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant validFrom,
        @Schema(description = "Novo fim inclusivo de validade. Omitir preserva; null explícito remove o limite final.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant validUntil
) {
    public UpdateNoticeRequestDTO(String title, String content, List<PublicationImageRequestDTO> images) {
        this(title, content, images, null, null, null);
    }
}
