package com.jeepclub.backend.dependents.api.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DependentsOpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDescribesDependentsContractsPrecisely() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/dependents']['post']['responses']['201']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/DependentResponseDTO"))
                .andExpect(jsonPath("$['paths']['/dependents']['post']['responses']['400']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/dependents']['post']['responses']['409']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/dependents']['get']['responses']['200']['content']['application/json']['schema']['type']")
                        .value("array"))
                .andExpect(jsonPath("$['paths']['/dependents']['get']['responses']['200']['content']['application/json']['schema']['items']['$ref']")
                        .value("#/components/schemas/DependentResponseDTO"))
                .andExpect(jsonPath("$['paths']['/dependents/{id}']['get']['responses']['403']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/dependents/{id}']['put']['responses']['409']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/dependents/{id}']['delete']['responses']['409']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['x-required-permissions'][0]")
                        .value("DEPENDENTS_DEPENDENT_READ"))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['parameters'][*].name")
                        .value(hasItems("q", "name", "cpf", "ids", "userId", "userIds", "relationshipType", "status", "fields", "page", "size", "sort")))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['parameters'][?(@.name == 'ids')].schema.type")
                        .value(hasItem("array")))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['parameters'][?(@.name == 'userIds')].schema.type")
                        .value(hasItem("array")))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['parameters'][?(@.name == 'userId')].schema.type")
                        .value(hasItem("integer")))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/PageResponseAdminDependentResponseDTO"))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['responses']['400']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/admin/dependents']['get']['responses']['403']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['x-required-permissions'][0]")
                        .value("DEPENDENTS_DEPENDENT_EXPORT"))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['parameters'][*].name")
                        .value(hasItems("format", "id", "ids", "userId", "userIds", "name", "status")))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['parameters'][?(@.name == 'id')].schema.type")
                        .value(hasItem("integer")))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['parameters'][?(@.name == 'ids')].schema.type")
                        .value(hasItem("array")))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['parameters'][?(@.name == 'userId')].schema.type")
                        .value(hasItem("integer")))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['parameters'][?(@.name == 'userIds')].schema.type")
                        .value(hasItem("array")))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['responses']['400']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/admin/dependents/export']['get']['responses']['404']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/admin/dependents/{id}']['get']['x-required-permissions'][0]")
                        .value("DEPENDENTS_DEPENDENT_READ"))
                .andExpect(jsonPath("$['paths']['/admin/dependents/{id}']['get']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/AdminDependentResponseDTO"))
                .andExpect(jsonPath("$['paths']['/admin/dependents/{id}']['get']['responses']['404']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/users/{userId}/dependents']['get']['x-required-permissions'][0]")
                        .value("DEPENDENTS_DEPENDENT_READ"))
                .andExpect(jsonPath("$['paths']['/users/{userId}/dependents']['get']['responses']['200']['content']['application/json']['schema']['type']")
                        .value("array"))
                .andExpect(jsonPath("$['paths']['/users/{userId}/dependents']['get']['responses']['200']['content']['application/json']['schema']['items']['$ref']")
                        .value("#/components/schemas/DependentResponseDTO"))
                .andExpect(jsonPath("$['paths']['/users/{userId}/dependents']['get']['responses']['403']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$['paths']['/users/{userId}/dependents/{id}']['get']['responses']['404']['content']['application/problem+json']['schema']['$ref']")
                        .value("#/components/schemas/ApiErrorResponse"));
    }
}
