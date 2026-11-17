package com.jeepclub.backend.dependents.api.controller;

import com.jeepclub.backend.dependents.core.application.query.AdminDependentFilter;
import com.jeepclub.backend.dependents.core.domain.enums.DependentStatus;
import com.jeepclub.backend.dependents.core.domain.enums.RelationshipType;
import com.jeepclub.backend.dependents.infra.persistence.entity.DependentEntity;
import com.jeepclub.backend.dependents.infra.persistence.entity.DependentHistoryEntity;
import com.jeepclub.backend.health.core.domain.enums.BloodType;
import com.jeepclub.backend.health.core.domain.enums.MedicalProfileOwnerType;
import com.jeepclub.backend.health.infra.persistence.entity.MedicalProfileEntity;
import com.jeepclub.backend.iam.authentication.core.application.service.security.AccessTokenAuthenticationService;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.iam.identity.infra.persistence.entity.UserEntity;
import com.jeepclub.backend.platform.security.authorization.UserAuthoritiesProvider;
import com.jeepclub.backend.platform.security.jwt.JwtAuthenticatedUser;
import com.jeepclub.backend.platform.security.jwt.JwtTokenParser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jeepclub.backend.dependents.api.controller.AdminDependentSearchHttpIntegrationTest$Sql")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminDependentSearchHttpIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    EntityManager em;
    @Autowired
    UserQuery identities;
    @MockitoBean
    JwtTokenParser jwt;
    @MockitoBean
    UserAuthoritiesProvider authorities;
    @MockitoBean
    AccessTokenAuthenticationService authentication;

    @BeforeEach
    void auth() {
        when(jwt.parseAndValidate("search-token"))
                .thenReturn(new JwtAuthenticatedUser(99L, 199L, "Admin", Instant.now().plusSeconds(3600)));
        when(authorities.findAuthorityCodesByUserId(99L))
                .thenReturn(List.of("DEPENDENTS_DEPENDENT_READ"));
    }

    @Test
    void ownerNameIsAppliedBeforePageCountAndSelectedFieldsDoNotLeakAuxiliaryIdOrStorageKey() throws Exception {
        long owner = owner("Literal %_! Titular");
        var first = dependent(
                owner,
                "Ana Dependent",
                "11122233344",
                RelationshipType.CHILD,
                "11999990001",
                "private-images/2026/06/30/00000000-0000-0000-0000-000000000001.jpg"
        );
        var second = dependent(owner, "Bruno Dependent", "22233344455", RelationshipType.SPOUSE, "11999990002", null);
        dependent(owner("Outro Titular"), "Carlos Dependent", "33344455566", RelationshipType.CHILD, "11999990003", null);

        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("q", " %_! ")
                        .param("name", "dependent")
                        .param("size", "1")
                        .param("page", "1")
                        .param("sort", "name,asc")
                        .param("fields", "ID,OWNER_NAME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.content[0].ownerName").value("Literal %_! Titular"))
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[0].cpf").doesNotExist())
                .andExpect(jsonPath("$.content[0].photoStorageKey").doesNotExist());

        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("userId", Long.toString(owner))
                        .param("fields", "PHOTO_URL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].photoUrl").value("/users/" + owner + "/dependents/" + first.getId() + "/photo"))
                .andExpect(jsonPath("$.content[0].id").doesNotExist())
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[0].photoStorageKey").doesNotExist());

        mvc.perform(get("/admin/dependents/" + first.getId())
                        .header("Authorization", "Bearer search-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.getId()))
                .andExpect(jsonPath("$.ownerName").value("Literal %_! Titular"))
                .andExpect(jsonPath("$.photoUrl").value("/users/" + owner + "/dependents/" + first.getId() + "/photo"))
                .andExpect(jsonPath("$.photoStorageKey").doesNotExist());

        assertThat(identities.findIdsByName(" %_! ")).containsExactly(owner);
    }

    @Test
    void qUsesOrWithConditionalNumericMatchingAndAlphanumericDoesNotMatchCpfOrPhone() throws Exception {
        long owner = owner("Roberto Titular");
        var row = dependent(owner, "Maria !%_ Silva", "52998224725", RelationshipType.SPOUSE, "11988887777", null);
        var numericOther = dependent(owner("Outro Socio"), "Lucas Costa", "99900012345", RelationshipType.CHILD, "11912345678", null);

        for (String q : List.of("529.982.247-25", "(11) 98888-7777", "!%_", "maria !%_", "Roberto Titular")) {
            mvc.perform(get("/admin/dependents")
                            .header("Authorization", "Bearer search-token")
                            .param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(row.getId()));
        }

        // Alphanumeric term "maria123" must NOT strip letters and match numericOther's CPF/phone containing "123"
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("q", "maria123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Formatted CPF filter and relationshipType filter combine with AND
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("cpf", " 529.982.247-25 ")
                        .param("relationshipType", "SPOUSE")
                        .param("userId", Long.toString(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(row.getId()));

        numericOther.setStatus(DependentStatus.ARCHIVED);
        em.flush();

        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(numericOther.getId()));
    }

    @Test
    void broadOwnerMatchesPreserveEveryOrBranchAndDatabasePagination() throws Exception {
        DependentEntity lastOwnerMatch = null;
        for (int i = 0; i < 501; i++) {
            long id = owner("XYZ Broad Dependent Owner");
            if (i == 500) {
                em.find(UserEntity.class, id).setStatus(UserStatus.ARCHIVED);
            }
            lastOwnerMatch = dependent(
                    id,
                    i == 500 ? "XYZ dependent" : "Regular Name",
                    String.format("8%010d", i),
                    RelationshipType.CHILD,
                    "11900001111",
                    null
            );
        }
        long other = owner("Unrelated Owner");
        var byName = dependent(other, "XYZ child name", "70000000001", RelationshipType.CHILD, "11900002222", null);
        em.flush();
        em.clear();
        Sql.statements.get().clear();

        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("q", "xyz")
                        .param("page", "10")
                        .param("size", "50")
                        .param("fields", "ID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(502))
                .andExpect(jsonPath("$.totalPages").value(11))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(lastOwnerMatch.getId()))
                .andExpect(jsonPath("$.content[1].id").value(byName.getId()))
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[0].ownerName").doesNotExist());

        assertThat(Sql.statements.get()).hasSize(2).allSatisfy(sql ->
                assertThat(sql).contains("exists", "user_id"));
        String contentSql = Sql.statements.get().get(0);
        assertThat(contentSql.substring(0, contentSql.indexOf(" from ")))
                .contains(".id")
                .doesNotContain(".user_id", ".name", ".cpf", ".photo_storage_key");
        assertThat(contentSql).containsAnyOf("offset ? rows fetch first ? rows only", "limit ?,?");
    }

    @Test
    void multiOwnerAndMultiIdSelectionApplyOrWithinListsAndAcrossFiltersWithoutLeakingHistoryOrClinicalData() throws Exception {
        long ownerA = owner("Titular Família A");
        long ownerB = owner("Titular Família B");
        long ownerC = owner("Titular Família C");

        var depA1 = dependent(ownerA, "Helena Silva", "10020030040", RelationshipType.CHILD, "11911110001", "SECRET_STORAGE_SENTINEL");
        var depA2 = dependent(ownerA, "Igor Silva", "10020030041", RelationshipType.SPOUSE, "11911110002", null);
        depA2.setStatus(DependentStatus.INACTIVE);
        var depB1 = dependent(ownerB, "Helena Souza", "20030040050", RelationshipType.CHILD, "11922220001", null);
        var depC1 = dependent(ownerC, "Helena Costa", "30040050060", RelationshipType.CHILD, "11933330001", null);

        var history = new DependentHistoryEntity();
        history.setDependentId(888888L);
        history.setUserId(ownerA);
        history.setName("Helena Removida");
        history.setCpf("40050060070");
        history.setBirthDate(LocalDate.of(2011, 3, 10));
        history.setRelationshipType(RelationshipType.CHILD);
        history.setStatus(DependentStatus.ACTIVE);
        history.setDeletedByUserId(ownerA);
        history.setCreatedAt(Instant.parse("2026-06-30T12:00:00Z"));
        history.setDeletedAt(Instant.parse("2026-06-30T13:00:00Z"));
        em.persist(history);

        var medical = new MedicalProfileEntity();
        medical.setOwnerType(MedicalProfileOwnerType.DEPENDENT);
        medical.setOwnerId(depA1.getId());
        medical.setBloodType(BloodType.O_POSITIVE);
        medical.setAllergies("CLINICAL_SENTINEL");
        medical.setCreatedAt(Instant.parse("2026-06-30T12:00:00Z"));
        medical.setUpdatedAt(Instant.parse("2026-06-30T12:00:00Z"));
        em.persist(medical);
        em.flush();
        em.clear();
        Sql.statements.get().clear();

        // Selection across 2 owners with duplicate userIds, verifying batch owner resolution and no N+1
        var multiOwnerResponse = mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("userIds", Long.toString(ownerA), Long.toString(ownerB), Long.toString(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(depA1.getId()))
                .andExpect(jsonPath("$.content[0].ownerName").value("Titular Família A"))
                .andExpect(jsonPath("$.content[1].id").value(depA2.getId()))
                .andExpect(jsonPath("$.content[2].id").value(depB1.getId()))
                .andExpect(jsonPath("$.content[2].ownerName").value("Titular Família B"))
                .andReturn().getResponse().getContentAsString();
        assertThat(multiOwnerResponse).doesNotContain("CLINICAL_SENTINEL", "SECRET_STORAGE_SENTINEL", "Helena Costa", "Helena Removida");
        // 2 statements for dependents (page + count) + 1 batch statement for UserQuery.findByIds
        assertThat(Sql.statements.get()).hasSize(3);

        // Selection by multiple dependent IDs with duplicates and a removed historical ID (888888)
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("ids", Long.toString(depB1.getId()), Long.toString(depA1.getId()), Long.toString(depB1.getId()), "888888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(depA1.getId()))
                .andExpect(jsonPath("$.content[1].id").value(depB1.getId()));

        // Combined OR within lists and AND across filter groups (ids + userIds + name + status)
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("ids", Long.toString(depA1.getId()), Long.toString(depA2.getId()), Long.toString(depB1.getId()), Long.toString(depC1.getId()))
                        .param("userIds", Long.toString(ownerA), Long.toString(ownerB))
                        .param("name", "helena")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(depA1.getId()))
                .andExpect(jsonPath("$.content[1].id").value(depB1.getId()));

        // Nonexistent IDs in admin search return empty page (200 OK)
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("ids", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void invalidParametersAre400AndAuthorizationIsPreserved() throws Exception {
        Map<String, String> invalidParams = Map.ofEntries(
                Map.entry("fields", "PHOTO_STORAGE_KEY"),
                Map.entry("sort", "ownerName,asc"),
                Map.entry("userId", "0"),
                Map.entry("ids", "0"),
                Map.entry("userIds", "0"),
                Map.entry("status", "INVALID"),
                Map.entry("relationshipType", "INVALID"),
                Map.entry("q", "x".repeat(151)),
                Map.entry("page", "-1"),
                Map.entry("size", "0"),
                Map.entry("cpf", "---")
        );
        for (var invalid : invalidParams.entrySet()) {
            mvc.perform(get("/admin/dependents")
                            .header("Authorization", "Bearer search-token")
                            .param(invalid.getKey(), invalid.getValue()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }

        for (String param : List.of("ids", "userIds")) {
            for (String invalidValue : List.of("", "-5", "abc")) {
                mvc.perform(get("/admin/dependents")
                                .header("Authorization", "Bearer search-token")
                                .param(param, invalidValue))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            }
            String[] oversized = java.util.stream.LongStream.rangeClosed(1, AdminDependentFilter.MAX_IDS + 1)
                    .mapToObj(Long::toString)
                    .toArray(String[]::new);
            mvc.perform(get("/admin/dependents")
                            .header("Authorization", "Bearer search-token")
                            .param(param, oversized))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }

        // Ambiguous combination userId + userIds must return 400
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("userId", "1")
                        .param("userIds", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("cpf", "abc123"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token")
                        .param("page", Integer.toString(Integer.MAX_VALUE)))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/admin/dependents/0")
                        .header("Authorization", "Bearer search-token"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/admin/dependents/999999")
                        .header("Authorization", "Bearer search-token"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/admin/dependents")).andExpect(status().isUnauthorized());

        // DEPENDENTS_DEPENDENT_EXPORT does not grant access to operational READ endpoints
        when(authorities.findAuthorityCodesByUserId(99L)).thenReturn(List.of("DEPENDENTS_DEPENDENT_EXPORT"));
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/dependents/1")
                        .header("Authorization", "Bearer search-token"))
                .andExpect(status().isForbidden());

        when(authorities.findAuthorityCodesByUserId(99L)).thenReturn(List.of());
        mvc.perform(get("/admin/dependents")
                        .header("Authorization", "Bearer search-token"))
                .andExpect(status().isForbidden());
    }

    private long owner(String name) {
        var row = new UserEntity();
        row.setName(name);
        row.setCpf(String.format("%011d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000000L)));
        row.setStatus(UserStatus.ACTIVE);
        row.setCreatedAt(Instant.now());
        em.persist(row);
        return row.getId();
    }

    private DependentEntity dependent(
            long userId,
            String name,
            String cpf,
            RelationshipType relationshipType,
            String phoneNumber,
            String photoStorageKey
    ) {
        var row = new DependentEntity();
        row.setUserId(userId);
        row.setName(name);
        row.setCpf(cpf);
        row.setBirthDate(LocalDate.of(2012, 4, 15));
        row.setRelationshipType(relationshipType);
        row.setPhoneNumber(phoneNumber);
        row.setPhotoStorageKey(photoStorageKey);
        row.setStatus(DependentStatus.ACTIVE);
        row.setCreatedAt(Instant.parse("2026-06-30T12:00:00Z"));
        em.persist(row);
        em.flush();
        return row;
    }

    public static class Sql implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            if (sql.startsWith("select ")) {
                statements.get().add(sql);
            }
            return sql;
        }
    }
}
