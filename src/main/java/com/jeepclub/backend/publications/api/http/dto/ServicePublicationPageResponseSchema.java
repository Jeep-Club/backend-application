package com.jeepclub.backend.publications.api.http.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "PageResponseServicePublicationResponseDTO", description = "Página estável de serviços administrativos.")
public record ServicePublicationPageResponseSchema(
        List<ServicePublicationResponseDTO> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        int numberOfElements,
        boolean first,
        boolean last,
        boolean empty
) { }
