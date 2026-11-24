package com.jeepclub.backend.billing.api.http.controller;

import com.jeepclub.backend.billing.core.repository.*;
import com.jeepclub.backend.billing.core.domain.model.*;
import com.jeepclub.backend.billing.core.domain.enums.cycle.PaymentAcceptancePolicy;
import com.jeepclub.backend.iam.authentication.core.application.service.security.AccessTokenAuthenticationService;
import com.jeepclub.backend.platform.security.authorization.UserAuthoritiesProvider;
import com.jeepclub.backend.platform.security.jwt.*;
import com.jeepclub.backend.billing.core.port.*;
import com.jeepclub.backend.iam.authorization.api.module.RolePresentationQuery;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.memberships.api.module.MemberRegistrationQuery;
import com.jeepclub.backend.shared.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.jeepclub.backend.billing.support.BillingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(BillingHttpWorkflowIntegrationTest.TimeConfiguration.class)
@Transactional
class BillingHttpWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired ChargeDefinitionRepository definitions;
    @Autowired ChargeCycleRepository cycles;
    @Autowired MemberChargeRepository charges;
    @Autowired MemberPaymentRepository payments;
    @Autowired MemberRefundRepository refunds;
    @MockitoBean JwtTokenParser jwtTokenParser;
    @MockitoBean UserAuthoritiesProvider authoritiesProvider;
    @MockitoBean AccessTokenAuthenticationService authenticationService;
    @MockitoBean BillingMembershipPort members;
    @MockitoBean BillingAuthorizationPort roles;
    @MockitoBean RolePresentationQuery rolePresentation;
    @MockitoBean UserQuery identity;
    @MockitoBean MemberRegistrationQuery registrations;
    @MockitoBean BillingEventPort events;
    @MockitoBean FileStorage storage;
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary Clock billingTestClock() { return CLOCK; }
        @Bean org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer billingValidationClock() {
            return configuration -> configuration.clockProvider(() -> CLOCK);
        }
    }
    static final String DEFINITION = """
        {"name":"fee","description":"annual","defaultAmount":100,"recurrenceType":"ONE_TIME","required":true,"paymentAcceptancePolicy":"AFTER_DUE_DATE"}
        """;
    void auth(long user, String... permissions) {
        when(jwtTokenParser.parseAndValidate("billing-token")).thenReturn(new JwtAuthenticatedUser(user, user + 1000, "Billing user", NOW.plusSeconds(3600)));
        when(authoritiesProvider.findAuthorityCodesByUserId(user)).thenReturn(List.of(permissions));
    }
    static <T extends AbstractMockHttpServletRequestBuilder<T>> T token(T request) { return request.header("Authorization", "Bearer billing-token"); }
    static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String body) { return token(request.contentType("application/json").content(body)); }
    long id(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("id").asLong(); }
    MemberCharge seedCharge() {
        var d = definitions.save(ChargeDefinition.create("seed", null, AMOUNT, com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME,
                true, PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
        var cycle = cycles.save(ChargeCycle.generate(d, "seed-cycle", DUE, 99L, NOW));
        return charges.save(MemberCharge.create(10L, d.getId(), cycle.getId(), AMOUNT, DUE, PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
    }
    @Test void definitionAssignmentGenerationAndAdministrativeLifecycleArePubliclyConsistent() throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_CREATE", "BILLING_CHARGE_DEFINITION_UPDATE", "BILLING_CHARGE_DEFINITION_READ",
                "BILLING_CHARGE_ASSIGNMENT_CREATE", "BILLING_CHARGE_ASSIGNMENT_UPDATE", "BILLING_CHARGE_ASSIGNMENT_READ",
                "BILLING_CHARGE_CYCLE_GENERATE", "BILLING_CHARGE_CYCLE_READ", "BILLING_CHARGE_CYCLE_FINISH", "BILLING_CHARGE_CYCLE_ARCHIVE");
        long definition = id(mvc.perform(body(post("/billing/charge-definitions"), DEFINITION)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn());
        when(members.existsActiveMemberByUserId(10L)).thenReturn(true);
        when(members.existsActiveMemberByUserIdForUpdate(10L)).thenReturn(true);
        long assignment = id(mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/users/10", definition))).andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value(10)).andReturn());
        mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/users/10", definition))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_ASSIGNMENT_ALREADY_EXISTS"));
        mvc.perform(token(get("/billing/charge-definitions/{id}/assignments", definition))).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(assignment));
        var generated = mvc.perform(body(post("/billing/charge-definitions/{id}/cycles", definition), "{\"code\":\"SEP\",\"dueDate\":\"2026-09-15\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.createdMemberCharges").value(1)).andReturn();
        long cycle = json.readTree(generated.getResponse().getContentAsString()).get("chargeCycle").get("id").asLong();
        mvc.perform(token(get("/billing/charge-cycles/{id}", cycle))).andExpect(status().isOk());
        mvc.perform(token(get("/billing/charge-definitions/{id}/cycles", definition))).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(cycle));
        mvc.perform(token(patch("/billing/charge-cycles/{id}/finish", cycle))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINISHED"));
        mvc.perform(token(patch("/billing/charge-cycles/{id}/archive", cycle))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(token(patch("/billing/charge-definitions/{id}/deactivate", definition))).andExpect(status().isOk());
        mvc.perform(token(patch("/billing/charge-definitions/{id}/activate", definition))).andExpect(status().isOk());
        mvc.perform(body(put("/billing/charge-definitions/{id}", definition), DEFINITION.replace("annual", "changed"))).andExpect(status().isOk()).andExpect(jsonPath("$.description").value("changed"));
        mvc.perform(token(patch("/billing/charge-definitions/{id}/archive", definition))).andExpect(status().isOk());
        mvc.perform(token(patch("/billing/charge-definitions/{id}/activate", definition))).andExpect(status().isConflict());
        assertThat(cycles.findById(cycle).orElseThrow().getChargeDefinitionDescriptionSnapshot()).isEqualTo("annual");
        assertThat(charges.findByChargeCycleId(cycle)).allMatch(MemberCharge::isPending);
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void definitionNameRetainsCaseAndDuplicateReturnsControlledConflict() throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_CREATE", "BILLING_CHARGE_DEFINITION_UPDATE", "BILLING_CHARGE_DEFINITION_READ", "BILLING_EXPORT");
        String original = DEFINITION.replace("fee", "  MeNsAl  Jeep  ");
        long definition = id(mvc.perform(body(post("/billing/charge-definitions"), original))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("MeNsAl  Jeep")).andReturn());
        try {
            assertThat(jdbc.queryForObject("select name from billing_charge_definitions where id=?", String.class, definition)).isEqualTo("MeNsAl  Jeep");
            mvc.perform(body(post("/billing/charge-definitions"), DEFINITION.replace("fee", "MENSAL  JEEP")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_DEFINITION_ALREADY_EXISTS"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
            mvc.perform(body(put("/billing/charge-definitions/{id}", definition), DEFINITION.replace("fee", "  mensal  JEEP  ")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("mensal  JEEP"));
            mvc.perform(token(get("/billing/charge-definitions/{id}", definition))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("mensal  JEEP"));
            mvc.perform(token(get("/billing/charge-definitions"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].name").value("mensal  JEEP"));
            for (String format : List.of("CSV", "PDF")) {
                var response = mvc.perform(token(get("/billing/admin/definitions/export").param("id", Long.toString(definition)).param("format", format)))
                    .andExpect(status().isOk()).andReturn().getResponse();
                if (format.equals("CSV")) assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("mensal  JEEP");
                else try (var pdf = org.apache.pdfbox.Loader.loadPDF(response.getContentAsByteArray())) {
                    assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains("mensal  JEEP");
                }
            }
            assertThat(jdbc.queryForObject("select name from billing_charge_definitions where id=?", String.class, definition)).isEqualTo("mensal  JEEP");
        } finally {
            jdbc.update("delete from billing_charge_definitions where id=?", definition);
        }
    }
    MockMultipartHttpServletRequestBuilder upload(String route, long id, boolean update) {
        var builder = multipart(route, id).file(new MockMultipartFile("receiptFile", "receipt.pdf", "application/pdf", new byte[]{1}));
        if (update) builder.with(request -> { request.setMethod("PUT"); return request; });
        return token(builder.param("amount", "100.00").param("paymentMethod", "PIX").param("paidAt", NOW.toString()));
    }
    @Test void paymentRejectionResubmissionConfirmationAndRefundProtectFinancialData() throws Exception {
        var debt = seedCharge(); auth(10L); when(storage.store(any(), any())).thenReturn(new StoredFile("billing/payment-receipts/private-key.pdf"));
        long payment = id(mvc.perform(upload("/billing/member-charges/{id}/payments", debt.getId(), false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptStorageKey").doesNotExist()).andExpect(jsonPath("$.receiptUrl").exists()).andReturn());
        auth(99L, "BILLING_PAYMENT_REJECT");
        mvc.perform(body(patch("/billing/member-payments/{id}/reject", payment), "{\"rejectionReason\":\"unreadable\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        auth(10L); mvc.perform(upload("/billing/member-payments/{id}", payment, true)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_VALIDATION"));
        mvc.perform(token(post("/billing/member-payments/{id}/refund-request", payment))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REFUND_PAYMENT"));
        auth(99L, "BILLING_PAYMENT_CONFIRM", "BILLING_PAYMENT_READ", "BILLING_MEMBER_CHARGE_READ");
        mvc.perform(token(patch("/billing/member-payments/{id}/confirm", payment))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(token(get("/billing/member-payments/{id}", payment))).andExpect(status().isOk()).andExpect(jsonPath("$.receiptStorageKey").doesNotExist());
        mvc.perform(token(get("/billing/member-charges/{id}", debt.getId()))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(token(get("/billing/member-payments"))).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].receiptStorageKey").doesNotExist()).andExpect(jsonPath("$.pageable").doesNotExist());
        auth(10L); long refund = id(mvc.perform(token(post("/billing/member-payments/{id}/refund-request", payment))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED")).andReturn());
        mvc.perform(token(post("/billing/member-payments/{id}/refund-request", payment))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(refund)).andExpect(jsonPath("$.requestedByUserName").doesNotHaveJsonPath());
        mvc.perform(token(get("/billing/users/me/member-refunds"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].userName").doesNotHaveJsonPath());

        String detail = "/billing/member-refunds/" + refund;
        mvc.perform(get(detail)).andExpect(status().isUnauthorized());
        mvc.perform(token(get(detail))).andExpect(status().isForbidden());
        auth(99L, "BILLING_REFUND_READ");
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.membershipNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.requestedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"));
        verifyNoInteractions(identity, registrations);

        when(identity.findByIds(anyCollection())).thenReturn(List.of(billingUser(10L, "Titular"), billingUser(99L, "Operador")));
        when(registrations.currentNumbers(List.of(10L))).thenReturn(Map.of(10L, "00042"));
        auth(99L, "BILLING_REFUND_APPROVE", "BILLING_REFUND_MARK_AS_REFUNDED", "BILLING_REFUND_READ",
                "IDENTITY_USER_READ", "MEMBERSHIP_MEMBER_READ");
        mvc.perform(token(patch("/billing/member-refunds/{id}/approve", refund))).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedByUserName").value("Operador"))
                .andExpect(jsonPath("$.requestedByUserName").value("Titular"));
        mvc.perform(token(patch("/billing/member-refunds/{id}/mark-as-refunded", refund))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedByUserName").value("Operador"))
                .andExpect(jsonPath("$.membershipNumber").value("00042"));
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedByUserName").value("Operador"))
                .andExpect(jsonPath("$.refundedByUserName").value("Operador"));
        clearInvocations(identity, registrations);
        mvc.perform(token(get("/billing/member-refunds")).param("page", "99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        verifyNoInteractions(identity, registrations);
        mvc.perform(token(get("/billing/member-refunds"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(refund))
                .andExpect(jsonPath("$.content[0].userName").value("Titular"))
                .andExpect(jsonPath("$.content[0].chargeDefinitionNameSnapshot").value("seed"));
        verify(identity).findByIds(List.of(10L));
        verify(registrations).currentNumbers(List.of(10L));
        mvc.perform(token(get("/billing/charge-cycles/{id}/member-refunds", debt.getChargeCycleId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].membershipNumber").value("00042"))
                .andExpect(jsonPath("$.content[0].chargeCycleCode").value("seed-cycle"));
        assertThat(payments.findById(payment).orElseThrow().getReceiptStorageKey()).isEqualTo("billing/payment-receipts/private-key.pdf");
    }
    @Test void memberOwnershipAndPendingPaymentConflictUseRfc9457WithoutInternals() throws Exception {
        var debt = seedCharge(); auth(20L);
        mvc.perform(token(get("/billing/me/member-charges/{id}", debt.getId()))).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("MEMBER_CHARGE_ACCESS_DENIED"))
                .andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(token(get("/billing/me/member-charges/{id}", debt.getId())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_CHARGE_ACCESS_DENIED"));
        mvc.perform(token(get("/billing/me/member-charges"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        auth(10L); mvc.perform(token(get("/billing/me/member-charges?status=PENDING"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.content[0].chargeCycleCode").value("seed-cycle"));
        mvc.perform(token(get("/billing/me/member-charges/{id}", debt.getId()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"));
        mvc.perform(token(get("/billing/me/member-charges/999999"))).andExpect(status().isNotFound());
        mvc.perform(get("/billing/me/member-charges")).andExpect(status().isUnauthorized());
        when(storage.store(any(), any())).thenReturn(new StoredFile("private-key"));
        mvc.perform(upload("/billing/member-charges/{id}/payments", debt.getId(), false)).andExpect(status().isCreated());
        mvc.perform(upload("/billing/member-charges/{id}/payments", debt.getId(), false)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_PAYMENT_ALREADY_EXISTS"));
        auth(99L, "BILLING_MEMBER_CHARGE_UPDATE");
        mvc.perform(body(patch("/billing/member-charges/{id}/final-amount", debt.getId()), "{\"finalAmount\":50}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_CHARGE_CANNOT_UPDATE_FINAL_AMOUNT"));
    }
    @Test void creatingUserAssignmentReturnsCurrentNamesWhenAuthorized() throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_CREATE", "BILLING_CHARGE_ASSIGNMENT_CREATE", "IDENTITY_USER_READ");
        when(members.existsActiveMemberByUserIdForUpdate(10L)).thenReturn(true);
        when(identity.findByIds(List.of(10L))).thenReturn(List.of(billingUser(10L, "Nome atual")));
        long definition = id(mvc.perform(body(post("/billing/charge-definitions"), DEFINITION))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/users/10", definition)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(10))
                .andExpect(jsonPath("$.userName").value("Nome atual"))
                .andExpect(jsonPath("$.chargeDefinitionName").value("fee"))
                .andExpect(jsonPath("$.eventTitle").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void deletionHistoryKeepsSnapshotAndOnlyShowsActiveAuthorWithPermission() throws Exception {
        var definition = definitions.save(ChargeDefinition.create("history fee", "original description", AMOUNT,
                com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME,
                true, PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
        long id = definition.getId();
        definitions.deleteWithHistory(id, 99L, NOW);
        String route = "/billing/charge-definitions/{id}/deletion-history";

        mvc.perform(get(route, id)).andExpect(status().isUnauthorized());
        auth(99L, "IDENTITY_USER_READ");
        mvc.perform(token(get(route, id))).andExpect(status().isForbidden());
        auth(99L, "BILLING_CHARGE_DEFINITION_HISTORY_READ");
        mvc.perform(token(get(route, id))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.deletedByUserId").value(99))
                .andExpect(jsonPath("$.deletedByUserName").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(identity);
        assertThat(jdbc.queryForObject("""
                select count(*) from platform_logs where actor_id = ? and action = ? and path = ?
                """, Integer.class, 99L, "BILLING_CHARGE_DEFINITION_HISTORY_READ", route.replace("{id}", Long.toString(id))))
                .isPositive();

        auth(99L, "BILLING_CHARGE_DEFINITION_HISTORY_READ", "IDENTITY_USER_READ");
        when(identity.findByIds(List.of(99L))).thenReturn(List.of(billingUser(99L, "Nome atual")));
        mvc.perform(token(get(route, id))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.originalId").value(id))
                .andExpect(jsonPath("$.name").value("history fee"))
                .andExpect(jsonPath("$.description").value("original description"))
                .andExpect(jsonPath("$.deletedByUserId").value(99))
                .andExpect(jsonPath("$.deletedByUserName").value("Nome atual"))
                .andExpect(jsonPath("$.schemaVersion").value(1));
        when(identity.findByIds(List.of(99L))).thenReturn(List.of(new UserDetails(
                99L, "Nome inativo", null, null, null, null, null, null, false, null, null, null)));
        mvc.perform(token(get(route, id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedByUserName").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(token(get(route, Long.MAX_VALUE))).andExpect(status().isNotFound());
    }

    @Test void assignmentReadPermissionAndIdentityFieldPermissionAreIndependent() throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_CREATE", "BILLING_CHARGE_ASSIGNMENT_CREATE");
        when(members.existsActiveMemberByUserIdForUpdate(10L)).thenReturn(true);
        long definition = id(mvc.perform(body(post("/billing/charge-definitions"), DEFINITION))
                .andExpect(status().isCreated()).andReturn());
        var list = "/billing/charge-definitions/" + definition + "/assignments";
        long assignment = id(mvc.perform(token(post(list + "/users/10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue())).andReturn());
        var detail = "/billing/charge-assignments/" + assignment;
        mvc.perform(get(list)).andExpect(status().isUnauthorized());
        mvc.perform(get(detail)).andExpect(status().isUnauthorized());
        mvc.perform(token(get(list))).andExpect(status().isForbidden());
        mvc.perform(token(get(detail))).andExpect(status().isForbidden());

        auth(99L, "BILLING_CHARGE_ASSIGNMENT_READ");
        mvc.perform(token(get(list))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userName").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(identity);

        auth(99L, "BILLING_CHARGE_ASSIGNMENT_READ", "IDENTITY_USER_READ");
        when(identity.findByIds(List.of(10L))).thenReturn(List.of(new UserDetails(
                10L, "Nome cadastral", null, null, null, null, null, null, true, null, null, null)));
        mvc.perform(token(get(list))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userName").value("Nome cadastral"));
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("Nome cadastral"));
        verify(identity, times(2)).findByIds(List.of(10L));

        auth(99L, "BILLING_CHARGE_ASSIGNMENT_UPDATE", "IDENTITY_USER_READ");
        mvc.perform(token(patch(detail + "/deactivate"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.userName").value("Nome cadastral"))
                .andExpect(jsonPath("$.chargeDefinitionName").value("fee"));
        mvc.perform(token(patch(detail + "/activate"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.userName").value("Nome cadastral"));

        auth(99L, "BILLING_CHARGE_ASSIGNMENT_READ", "IDENTITY_USER_READ");

        when(identity.findByIds(List.of(10L))).thenThrow(new IllegalStateException("internal provider marker"));
        mvc.perform(token(get(detail))).andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal provider marker"))));
    }

    @Test void administrativeChargesExposeIndependentFieldsAndBatchRepeatedCycles() throws Exception {
        var debt = seedCharge();
        for (long userId = 11; userId <= 30; userId++) {
            charges.save(MemberCharge.create(userId, debt.getChargeDefinitionId(), debt.getChargeCycleId(),
                    AMOUNT, DUE, PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
        }
        jdbc.update("update billing_charge_definitions set name=? where id=?", "renamed", debt.getChargeDefinitionId());
        entityManager.clear();
        var list = "/billing/member-charges";
        var detail = list + "/" + debt.getId();
        mvc.perform(get(list)).andExpect(status().isUnauthorized());
        mvc.perform(get(detail)).andExpect(status().isUnauthorized());
        auth(99L, "IDENTITY_USER_READ", "MEMBERSHIP_MEMBER_READ");
        mvc.perform(token(get(list))).andExpect(status().isForbidden());
        mvc.perform(token(get(detail))).andExpect(status().isForbidden());

        auth(99L, "BILLING_MEMBER_CHARGE_READ");
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.membershipNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"));
        verifyNoInteractions(identity, registrations);

        auth(99L, "BILLING_MEMBER_CHARGE_READ", "IDENTITY_USER_READ", "MEMBERSHIP_MEMBER_READ");
        mvc.perform(token(get(list)).param("page", "99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        verifyNoInteractions(identity, registrations);

        var user = new UserDetails(10L, "Nome cadastral", null, null, null, null, null, null, true, null, null, null);
        when(identity.findByIds(anyCollection())).thenReturn(List.of(user));
        when(registrations.currentNumbers(anyCollection())).thenReturn(Map.of(10L, "00042"));
        mvc.perform(token(get(list)).param("size", "50")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.content[?(@.userId == 10)].userName").value(org.hamcrest.Matchers.contains("Nome cadastral")))
                .andExpect(jsonPath("$.content[?(@.userId == 10)].membershipNumber").value(org.hamcrest.Matchers.contains("00042")));
        var ids = java.util.stream.LongStream.rangeClosed(10, 30).boxed().toList();
        verify(identity).findByIds(argThat(batch -> batch.size() == 21 && batch.containsAll(ids)));
        verify(registrations).currentNumbers(argThat(batch -> batch.size() == 21 && batch.containsAll(ids)));
        verifyNoMoreInteractions(identity, registrations);

        when(registrations.currentNumbers(List.of(10L))).thenReturn(Map.of(10L, "00043"));
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipNumber").value("00043"))
                .andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"));
    }

    @Test void inconsistentCycleDefinitionReturnsSanitizedInternalProblem() throws Exception {
        var debt = seedCharge();
        var cycleDefinition = definitions.save(ChargeDefinition.create("other", null, AMOUNT,
                com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME, true,
                PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
        jdbc.update("update billing_charge_cycles set charge_definition_id=? where id=?", cycleDefinition.getId(), debt.getChargeCycleId());
        entityManager.clear();
        auth(10L);
        mvc.perform(token(get("/billing/me/member-charges/{id}", debt.getId())))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("BILLING_DATA_INTEGRITY_ERROR"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(cycleDefinition.getId().toString()))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong-code"))));
    }
    @Test void memberChargeCycleLookupUsesOneBatchStatementRegardlessOfCycleCount() throws Exception {
        var statistics = entityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            auth(10L);
            var emptyBefore = statistics.getPrepareStatementCount();
            mvc.perform(token(get("/billing/me/member-charges")).param("page", "900").param("size", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
            entityManager.clear();
            var emptyStatements = statistics.getPrepareStatementCount() - emptyBefore;

            seedCharge();
            entityManager.flush(); entityManager.clear();
            var oneBefore = statistics.getPrepareStatementCount();
            mvc.perform(token(get("/billing/me/member-charges"))).andExpect(status().isOk());
            entityManager.clear();
            var oneStatements = statistics.getPrepareStatementCount() - oneBefore;

            var d2 = definitions.save(ChargeDefinition.create("second", null, AMOUNT,
                    com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME, true,
                    PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
            var c2 = cycles.save(ChargeCycle.generate(d2, "second-cycle", DUE, 99L, NOW));
            charges.save(MemberCharge.create(10L, d2.getId(), c2.getId(), AMOUNT, DUE,
                    PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
            var d2b = definitions.save(ChargeDefinition.create("second-b", null, AMOUNT,
                    com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME, true,
                    PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
            var c2b = cycles.save(ChargeCycle.generate(d2b, "second-b-cycle", DUE, 99L, NOW));
            for (int i = 0; i < 7; i++) {
                var extraDefinition = definitions.save(ChargeDefinition.create("second-b-" + i, null, AMOUNT,
                        com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME, true,
                        PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
                var extraCycle = cycles.save(ChargeCycle.generate(extraDefinition, "second-b-cycle-" + i, DUE, 99L, NOW));
                charges.save(MemberCharge.create(10L, extraDefinition.getId(), extraCycle.getId(), AMOUNT, DUE,
                        PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
            }
            entityManager.flush(); entityManager.clear();
            var repeatedBefore = statistics.getPrepareStatementCount();
            mvc.perform(token(get("/billing/me/member-charges")).param("size", "50"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(9));
            entityManager.clear();
            var repeatedStatements = statistics.getPrepareStatementCount() - repeatedBefore;

            var d3 = definitions.save(ChargeDefinition.create("third", null, AMOUNT,
                    com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME, true,
                    PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
            var c3 = cycles.save(ChargeCycle.generate(d3, "third-cycle", DUE, 99L, NOW));
            charges.save(MemberCharge.create(10L, d3.getId(), c3.getId(), AMOUNT, DUE,
                    PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
            entityManager.flush(); entityManager.clear();
            var distinctBefore = statistics.getPrepareStatementCount();
            mvc.perform(token(get("/billing/me/member-charges")).param("size", "50"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(10));
            entityManager.clear();
            var distinctStatements = statistics.getPrepareStatementCount() - distinctBefore;

            assertThat(emptyStatements).isLessThanOrEqualTo(2);
            assertThat(oneStatements).isEqualTo(2);
            assertThat(repeatedStatements).isEqualTo(oneStatements);
            assertThat(distinctStatements).isEqualTo(oneStatements);
        } finally { statistics.setStatisticsEnabled(false); }
    }
    @Test void historicalCycleMetadataSurvivesRenameAndArchiveAndAdminContractRemainsStable() throws Exception {
        var debt = seedCharge();
        var cycle = cycles.findById(debt.getChargeCycleId()).orElseThrow();
        cycle.finish(99L, NOW); cycle.archive(99L, NOW); cycles.save(cycle);
        jdbc.update("update billing_charge_definitions set name=? where id=?", "renamed", debt.getChargeDefinitionId());
        auth(10L);
        mvc.perform(token(get("/billing/me/member-charges").param("size", "100").param("sort", "id,desc")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content[0].id").value(debt.getId()))
                .andExpect(jsonPath("$.content[0].chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.content[0].chargeCycleCode").value("seed-cycle"));
        mvc.perform(token(get("/billing/me/member-charges/{id}", debt.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"));
        auth(99L, "BILLING_MEMBER_CHARGE_READ");
        mvc.perform(token(get("/billing/member-charges/{id}", debt.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"))
                .andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"));
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"name\":\" \",\"defaultAmount\":0}", "{\"name\":\"fee\",\"recurrenceType\":\"INVALID\"}"})
    void invalidDefinitionPayloadUsesBeanValidationProblem(String payload) throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_CREATE");
        mvc.perform(body(post("/billing/charge-definitions"), payload)).andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
    @Test void missingAndInvalidIdsAndMultipartUseControlledProblems() throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_READ", "BILLING_PAYMENT_READ", "BILLING_REFUND_READ", "BILLING_CHARGE_ASSIGNMENT_READ", "BILLING_CHARGE_CYCLE_READ");
        for (var path : List.of("/billing/charge-definitions/999999", "/billing/member-payments/999999", "/billing/member-refunds/999999", "/billing/charge-assignments/999999", "/billing/charge-cycles/999999")) {
            mvc.perform(token(get(path))).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(token(get("/billing/charge-definitions/0"))).andExpect(status().isBadRequest());
        auth(10L);
        mvc.perform(token(multipart("/billing/member-charges/1/payments").param("amount", "0")))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
    @Test void authorityIsSpecificToFinancialActionAndUnauthenticatedRequestsAreRejected() throws Exception {
        var routes = List.of(get("/billing/member-charges"), get("/billing/member-payments"), get("/billing/member-refunds"),
                get("/billing/charge-definitions"), patch("/billing/member-payments/1/confirm"), patch("/billing/member-refunds/1/approve"),
                patch("/billing/member-refunds/1/expire"), patch("/billing/charge-cycles/1/cancel"), patch("/billing/member-charges/1/cancel"));
        for (var route : routes) mvc.perform(route).andExpect(status().isUnauthorized());
        auth(99L, "BILLING_EXPORT");
        for (var route : routes) mvc.perform(token(route)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    @Test void allAssignmentHttpKindsAndLifecyclePreserveTheirTargets() throws Exception {
        auth(99L, "BILLING_CHARGE_DEFINITION_CREATE", "BILLING_CHARGE_DEFINITION_READ", "BILLING_CHARGE_ASSIGNMENT_CREATE", "BILLING_CHARGE_ASSIGNMENT_UPDATE", "BILLING_CHARGE_ASSIGNMENT_READ", "AUTHORIZATION_ROLE_READ");
        long definition = id(mvc.perform(body(post("/billing/charge-definitions"), DEFINITION)).andExpect(status().isCreated()).andReturn());
        mvc.perform(token(get("/billing/charge-definitions/{id}", definition))).andExpect(status().isOk());
        mvc.perform(token(get("/billing/charge-definitions"))).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("fee"));
        when(roles.existsActiveRoleByIdForUpdate(20L)).thenReturn(true); when(events.existsEventById(30L)).thenReturn(true);
        for (String suffix : List.of("all-members", "roles/20", "events/30/participants")) {
            long assignment = id(mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/" + suffix, definition))).andExpect(status().isCreated()).andReturn());
            mvc.perform(token(get("/billing/charge-assignments/{id}", assignment))).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
            mvc.perform(token(patch("/billing/charge-assignments/{id}/deactivate", assignment))).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
            mvc.perform(token(patch("/billing/charge-assignments/{id}/activate", assignment))).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
            mvc.perform(token(patch("/billing/charge-assignments/{id}/activate", assignment))).andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }
    @Test void roleAssignmentsRequireBothPermissionsAndHideRoleDetailsWithoutAuthorization() throws Exception {
        var definition = definitions.save(ChargeDefinition.create("permissions", null, AMOUNT, com.jeepclub.backend.billing.core.domain.enums.ChargeRecurrenceType.ONE_TIME, true, PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
        long definitionId = definition.getId();
        when(roles.existsActiveRoleByIdForUpdate(20L)).thenReturn(true);
        when(rolePresentation.findByIds(anyCollection())).thenReturn(List.of(new RolePresentationQuery.Details(20L, "Diretoria")));

        auth(99L, "BILLING_CHARGE_ASSIGNMENT_CREATE");
        mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/roles/20", definitionId))).andExpect(status().isForbidden());
        verify(roles, never()).existsActiveRoleByIdForUpdate(20L);
        mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/all-members", definitionId))).andExpect(status().isCreated());
        auth(99L, "AUTHORIZATION_ROLE_READ");
        mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/roles/20", definitionId))).andExpect(status().isForbidden());
        auth(99L, "BILLING_CHARGE_ASSIGNMENT_CREATE", "AUTHORIZATION_ROLE_READ");
        long assignmentId = id(mvc.perform(token(post("/billing/charge-definitions/{id}/assignments/roles/20", definitionId))).andExpect(status().isCreated()).andExpect(jsonPath("$.roleName").value("Diretoria")).andReturn());

        auth(99L, "BILLING_CHARGE_ASSIGNMENT_READ", "AUTHORIZATION_ROLE_READ");
        mvc.perform(token(get("/billing/charge-definitions/{id}/assignments", definitionId))).andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.audienceType == 'ROLE')].roleId").value(org.hamcrest.Matchers.hasItem(20))).andExpect(jsonPath("$.content[?(@.audienceType == 'ROLE')].roleName").value(org.hamcrest.Matchers.hasItem("Diretoria")));
        auth(99L, "BILLING_CHARGE_ASSIGNMENT_READ", "BILLING_CHARGE_ASSIGNMENT_UPDATE");
        mvc.perform(token(get("/billing/charge-assignments/{id}", assignmentId))).andExpect(status().isOk()).andExpect(jsonPath("$.roleId").doesNotExist()).andExpect(jsonPath("$.roleName").doesNotExist());
        mvc.perform(token(get("/billing/charge-definitions/{id}/assignments", definitionId))).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.content[?(@.audienceType == 'ROLE')].roleId").value(org.hamcrest.Matchers.contains((Object) null))).andExpect(jsonPath("$.content[?(@.audienceType == 'ROLE')].roleName").value(org.hamcrest.Matchers.contains((Object) null)));
        mvc.perform(token(patch("/billing/charge-assignments/{id}/deactivate", assignmentId))).andExpect(status().isOk()).andExpect(jsonPath("$.roleId").doesNotExist());
        auth(99L, "AUTHORIZATION_ROLE_READ");
        mvc.perform(token(get("/billing/charge-definitions/{id}/assignments", definitionId))).andExpect(status().isForbidden());
    }
    @Test void administrativeChargeMutationsPreserveTheSameContextAsDetail() throws Exception {
        var debt = seedCharge();
        when(identity.findByIds(List.of(10L))).thenReturn(List.of(billingUser(10L, "Titular")));
        when(registrations.currentNumbers(List.of(10L))).thenReturn(Map.of(10L, "00042"));
        var amount = "/billing/member-charges/{id}/final-amount";
        var cancel = "/billing/member-charges/{id}/cancel";
        mvc.perform(patch(amount, debt.getId()).contentType("application/json").content("{\"finalAmount\":50}"))
                .andExpect(status().isUnauthorized());
        auth(99L, "BILLING_MEMBER_CHARGE_READ");
        mvc.perform(body(patch(amount, debt.getId()), "{\"finalAmount\":50}")).andExpect(status().isForbidden());
        mvc.perform(token(patch(cancel, debt.getId()))).andExpect(status().isForbidden());
        verifyNoInteractions(identity, registrations);

        auth(99L, "BILLING_MEMBER_CHARGE_UPDATE", "BILLING_MEMBER_CHARGE_CANCEL");
        mvc.perform(body(patch(amount, debt.getId()), "{\"finalAmount\":75}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.membershipNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"));
        verifyNoInteractions(identity, registrations);

        auth(99L, "BILLING_MEMBER_CHARGE_UPDATE", "BILLING_MEMBER_CHARGE_CANCEL", "BILLING_MEMBER_CHARGE_READ",
                "IDENTITY_USER_READ", "MEMBERSHIP_MEMBER_READ");
        assertChargeContext(mvc.perform(body(patch(amount, debt.getId()), "{\"finalAmount\":50}")))
                .andExpect(jsonPath("$.finalAmount").value(50));
        mvc.perform(token(get("/billing/member-charges?userId=10&status=PENDING"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        var cycle = cycles.findById(debt.getChargeCycleId()).orElseThrow();
        cycle.finish(99L, NOW);
        cycle.archive(99L, NOW);
        cycles.save(cycle);
        assertChargeContext(mvc.perform(token(patch(cancel, debt.getId()))))
                .andExpect(jsonPath("$.status").value("CANCELED"));
        assertChargeContext(mvc.perform(token(get("/billing/member-charges/{id}", debt.getId()))));
        auth(10L);
        mvc.perform(token(get("/billing/me/member-charges/{id}", debt.getId()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.userName").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.membershipNumber").doesNotHaveJsonPath());
    }

    private ResultActions assertChargeContext(ResultActions response) throws Exception {
        return response.andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("Titular"))
                .andExpect(jsonPath("$.membershipNumber").value("00042"))
                .andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.chargeCycleCode").value("seed-cycle"));
    }

    @Test void administrativePaymentPageAndMutationsCarryAuthorizedContext() throws Exception {
        var debt = seedCharge();
        var definition = definitions.findById(debt.getChargeDefinitionId()).orElseThrow();
        var secondCycle = cycles.save(ChargeCycle.generate(definition, "second-cycle", DUE, 99L, NOW));
        var second = charges.save(MemberCharge.create(10L, definition.getId(), secondCycle.getId(),
                AMOUNT, DUE, PaymentAcceptancePolicy.AFTER_DUE_DATE, null, NOW));
        when(storage.store(any(), any())).thenReturn(new StoredFile("billing/payment-receipts/first.pdf"),
                new StoredFile("billing/payment-receipts/second.pdf"), new StoredFile("billing/payment-receipts/replacement.pdf"));
        auth(10L);
        long firstPayment = id(mvc.perform(upload("/billing/member-charges/{id}/payments", debt.getId(), false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userName").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.confirmedByUserName").doesNotHaveJsonPath()).andReturn());
        long secondPayment = id(mvc.perform(upload("/billing/member-charges/{id}/payments", second.getId(), false))
                .andExpect(status().isCreated()).andReturn());
        jdbc.update("update billing_charge_definitions set name=? where id=?", "renamed", debt.getChargeDefinitionId());
        entityManager.clear();

        String list = "/billing/member-payments";
        String detail = list + "/" + firstPayment;
        mvc.perform(get(list)).andExpect(status().isUnauthorized());
        auth(99L, "IDENTITY_USER_READ", "MEMBERSHIP_MEMBER_READ");
        mvc.perform(token(get(detail))).andExpect(status().isForbidden());
        auth(99L, "BILLING_PAYMENT_READ");
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.membershipNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.confirmedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.rejectedByUserName").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(identity, registrations);

        auth(99L, "BILLING_PAYMENT_READ", "BILLING_PAYMENT_CONFIRM", "BILLING_PAYMENT_REJECT",
                "IDENTITY_USER_READ", "MEMBERSHIP_MEMBER_READ");
        mvc.perform(token(get(list)).param("page", "99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        verifyNoInteractions(identity, registrations);
        when(identity.findByIds(List.of(10L))).thenReturn(List.of(billingUser(10L, "Titular")));
        when(identity.findByIds(List.of(10L, 99L))).thenReturn(List.of(billingUser(10L, "Titular"), billingUser(99L, "Operador")));
        when(registrations.currentNumbers(List.of(10L))).thenReturn(Map.of(10L, "00042"));
        mvc.perform(token(get(list)).param("status", "PENDING_VALIDATION").param("sort", "id,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(firstPayment))
                .andExpect(jsonPath("$.content[1].id").value(secondPayment))
                .andExpect(jsonPath("$.content[0].userId").value(10))
                .andExpect(jsonPath("$.content[0].userName").value("Titular"))
                .andExpect(jsonPath("$.content[0].membershipNumber").value("00042"))
                .andExpect(jsonPath("$.content[0].chargeDefinitionId").value(debt.getChargeDefinitionId()))
                .andExpect(jsonPath("$.content[0].chargeCycleId").value(debt.getChargeCycleId()))
                .andExpect(jsonPath("$.content[0].chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.content[0].chargeCycleCode").value("seed-cycle"))
                .andExpect(jsonPath("$.content[0].dueDate").value(DUE.toString()));
        verify(identity).findByIds(List.of(10L));
        verify(registrations).currentNumbers(List.of(10L));

        mvc.perform(token(patch(detail + "/confirm"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.userName").value("Titular"))
                .andExpect(jsonPath("$.membershipNumber").value("00042"))
                .andExpect(jsonPath("$.chargeDefinitionNameSnapshot").value("seed"))
                .andExpect(jsonPath("$.confirmedByUserId").value(99))
                .andExpect(jsonPath("$.confirmedByUserName").value("Operador"))
                .andExpect(jsonPath("$.receiptStorageKey").doesNotHaveJsonPath());
        mvc.perform(body(patch(list + "/{id}/reject", secondPayment), "{\"rejectionReason\":\"invalid receipt\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.userName").value("Titular"))
                .andExpect(jsonPath("$.chargeCycleCode").value("second-cycle"))
                .andExpect(jsonPath("$.rejectedByUserName").value("Operador"));
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedByUserName").value("Operador"));

        auth(99L, "BILLING_PAYMENT_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("Titular"))
                .andExpect(jsonPath("$.membershipNumber").value(org.hamcrest.Matchers.nullValue()));
        auth(99L, "BILLING_PAYMENT_READ", "MEMBERSHIP_MEMBER_READ");
        mvc.perform(token(get(detail))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.confirmedByUserId").value(99))
                .andExpect(jsonPath("$.confirmedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.membershipNumber").value("00042"));

        auth(10L);
        mvc.perform(upload("/billing/member-payments/{id}", secondPayment, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VALIDATION"))
                .andExpect(jsonPath("$.userName").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.membershipNumber").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.rejectedByUserName").doesNotHaveJsonPath());
    }

    private static UserDetails billingUser(long userId, String name) {
        return new UserDetails(userId, name, null, null, null, null, null, null, true, null, null, null);
    }

    @Test void refundRequestsAndAdministrativeRejectCancelExpireFollowSpecificPermissionsAndStates() throws Exception {
        var request = refunds.save(com.jeepclub.backend.billing.support.BillingFixtures.request());
        when(identity.findByIds(anyCollection())).thenReturn(List.of(billingUser(99L, "Operador")));
        auth(99L, "BILLING_REFUND_REJECT", "BILLING_REFUND_CANCEL", "BILLING_REFUND_EXPIRE", "IDENTITY_USER_READ");
        mvc.perform(body(patch("/billing/member-refunds/{id}/reject", request.getId()), "{\"rejectionReason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(body(patch("/billing/member-refunds/{id}/reject", request.getId()), "{\"rejectionReason\":\"not refundable\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedByUserName").value("Operador"));
        var canceled = refunds.save(com.jeepclub.backend.billing.support.BillingFixtures.request());
        mvc.perform(token(patch("/billing/member-refunds/{id}/cancel", canceled.getId()))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canceledByUserName").value("Operador"));
        var expired = refunds.save(MemberRefund.createEligibilityForCanceledCycle(2L, 1L, 4L, 10L, AMOUNT, 99L, NOW.minusSeconds(100), NOW, NOW));
        mvc.perform(token(patch("/billing/member-refunds/{id}/expire", expired.getId()))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.createdByUserName").value("Operador"))
                .andExpect(jsonPath("$.canceledByUserName").value(org.hamcrest.Matchers.nullValue()));
        var eligible = refunds.save(eligibility()); auth(20L);
        mvc.perform(token(patch("/billing/member-refunds/{id}/request", eligible.getId()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REFUND_ACCESS_DENIED"));
        auth(10L); mvc.perform(token(patch("/billing/member-refunds/{id}/request", eligible.getId()))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"));
    }
}
