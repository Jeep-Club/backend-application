package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

@Schema(description = "Criação de aviso em DRAFT. Autor, status e timestamps são definidos pelo servidor.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateNoticeRequestDTO(
        @Schema(description = "Título do aviso.", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "Conteúdo textual do aviso.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String content,
        @Schema(description = "Galeria com 1 a 5 imagens, exatamente uma principal, posições contínuas e chaves únicas.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Size(min = 1, max = 5) List<@NotNull @Valid PublicationImageRequestDTO> images,
        @Schema(description = "Prioridade editorial do aviso. Quando omitida na criação, assume NORMAL.",
                defaultValue = "NORMAL", allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"},
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        NoticePriority priority,
        @Schema(description = "Início inclusivo opcional da janela de validade temporal no feed.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant validFrom,
        @Schema(description = "Fim inclusivo opcional da janela de validade temporal no feed.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant validUntil
) {
    public CreateNoticeRequestDTO(String title, String content, List<PublicationImageRequestDTO> images) {
        this(title, content, images, null, null, null);
    }
}
