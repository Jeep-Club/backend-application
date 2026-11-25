package com.jeepclub.backend.identity.core.application.service.user;

import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.iam.identity.api.module.spi.UserMembershipNumberProjection;
import com.jeepclub.backend.iam.identity.core.application.query.user.AdminUserField;
import com.jeepclub.backend.iam.identity.core.application.query.user.AdminUserFilter;
import com.jeepclub.backend.iam.identity.core.application.query.user.UserExportLayout;
import com.jeepclub.backend.iam.identity.core.application.result.admin.user.AdminUserResult;
import com.jeepclub.backend.iam.identity.core.application.service.user.UserExportService;
import com.jeepclub.backend.iam.identity.core.repository.AdminUserQueryRepository;
import com.jeepclub.backend.shared.export.ExportDocument;
import com.jeepclub.backend.shared.export.ExportException;
import com.jeepclub.backend.shared.export.ExportFile;
import com.jeepclub.backend.shared.export.ExportFormat;
import com.jeepclub.backend.shared.export.ExportRenderer;
import com.jeepclub.backend.shared.export.ExportRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserExportServiceTest {

    @Mock
    private AdminUserQueryRepository query;
    @Mock
    private ExportRenderer renderer;
    @Mock
    private UserMembershipNumberProjection membershipNumbers;

    @InjectMocks
    private UserExportService service;

    @Test
    void rejectsAmbiguousScalarIdAndIdsList() {
        AdminUserFilter filter = emptyFilter(1L);
        assertThatThrownBy(() -> service.export(filter, List.of(1L), ExportFormat.CSV, UserExportLayout.MEMBER))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);
    }

    @Test
    void rejectsEmptyOversizedOrNonPositiveIds() {
        AdminUserFilter filter = emptyFilter(null);
        List<Long> oversized = LongStream.rangeClosed(1, UserExportService.MAX_IDS + 1L).boxed().toList();

        assertThatThrownBy(() -> service.export(filter, List.of(), ExportFormat.CSV, UserExportLayout.MEMBER))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);

        assertThatThrownBy(() -> service.export(filter, oversized, ExportFormat.CSV, UserExportLayout.MEMBER))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);

        assertThatThrownBy(() -> service.export(filter, List.of(1L, 0L), ExportFormat.CSV, UserExportLayout.MEMBER))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);
    }

    @Test
    void rejectsWhenAnySelectedUserDoesNotExist() {
        AdminUserFilter filter = emptyFilter(null);
        when(query.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.export(filter, List.of(99L), ExportFormat.CSV, UserExportLayout.MEMBER))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.NOT_FOUND);

        when(query.countByIds(List.of(10L, 20L))).thenReturn(1L);
        assertThatThrownBy(() -> service.export(filter, List.of(10L, 20L), ExportFormat.CSV, UserExportLayout.MEMBER))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.NOT_FOUND);
    }

    @Test
    void deduplicatesIdsAppliesFilterLabelsAndPreservesLayouts() {
        AdminUserFilter filter = new AdminUserFilter(
                null, "Ana", null, null, null, null, null, UserStatus.ACTIVE,
                null, null, null, null, null
        );
        when(query.countByIds(List.of(10L, 30L))).thenReturn(2L);
        when(query.findAll(any(AdminUserFilter.class), eq(EnumSet.allOf(AdminUserField.class)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        user(10L, "Ana Maria", "52998224725"),
                        user(30L, "Ana Paula", "11144477735")
                )));
        when(membershipNumbers.currentNumbers(List.of(10L, 30L))).thenReturn(Map.of(10L, "00010", 30L, "00030"));
        when(renderer.render(any(ExportDocument.class), eq(ExportFormat.PDF))).thenAnswer(inv -> {
            ExportDocument doc = inv.getArgument(0);
            List<ExportRow> rows = new ArrayList<>();
            doc.rows().read(rows::add);
            assertThat(rows).hasSize(2);
            assertThat(doc.columns().get(0)).isEqualTo("Matrícula");
            assertThat(doc.columns().get(doc.columns().size() - 1)).isEqualTo("ID técnico");
            assertThat(rows.get(0).cells().get(0)).isEqualTo("00010");
            assertThat(rows.get(0).cells().get(12)).isEqualTo("10");
            return new ExportFile("usuarios.pdf", "application/pdf", new byte[]{1});
        });

        service.export(filter, List.of(10L, 30L, 10L), ExportFormat.PDF, UserExportLayout.MEMBER);

        ArgumentCaptor<ExportDocument> docCaptor = ArgumentCaptor.forClass(ExportDocument.class);
        verify(renderer).render(docCaptor.capture(), eq(ExportFormat.PDF));
        assertThat(docCaptor.getValue().filters())
                .containsExactly("IDs técnicos: 10, 30", "Nome: Ana", "Situação: ACTIVE");

        ArgumentCaptor<AdminUserFilter> filterCaptor = ArgumentCaptor.forClass(AdminUserFilter.class);
        verify(query).findAll(filterCaptor.capture(), eq(EnumSet.allOf(AdminUserField.class)), any(Pageable.class));
        assertThat(filterCaptor.getValue().ids()).containsExactly(10L, 30L);

        when(renderer.render(any(ExportDocument.class), eq(ExportFormat.CSV))).thenAnswer(inv -> {
            ExportDocument doc = inv.getArgument(0);
            List<ExportRow> rows = new ArrayList<>();
            doc.rows().read(rows::add);
            assertThat(doc.columns().get(0)).isEqualTo("ID");
            assertThat(doc.columns().get(1)).isEqualTo("Matrícula");
            assertThat(rows.get(0).cells().get(0)).isEqualTo("10");
            assertThat(rows.get(0).cells().get(1)).isEqualTo("00010");
            return new ExportFile("usuarios.csv", "text/csv", new byte[]{1});
        });

        service.export(filter, List.of(10L, 30L), ExportFormat.CSV, UserExportLayout.TECHNICAL);
    }

    @Test
    void acceptsExactMaxIdsAndPreservesIdsAcrossResolvedMembershipAndDefaultMemberLayout() {
        List<Long> maxIds = LongStream.rangeClosed(1, UserExportService.MAX_IDS).boxed().toList();
        AdminUserFilter base = emptyFilter(null).withIds(maxIds);
        assertThat(base.resolvedMembership(10L, 20L).ids()).containsExactlyElementsOf(maxIds);

        when(query.countByIds(maxIds)).thenReturn((long) UserExportService.MAX_IDS);
        when(query.findAll(any(AdminUserFilter.class), eq(EnumSet.allOf(AdminUserField.class)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(renderer.render(any(ExportDocument.class), eq(ExportFormat.CSV))).thenAnswer(inv -> {
            ExportDocument doc = inv.getArgument(0);
            List<ExportRow> rows = new ArrayList<>();
            doc.rows().read(rows::add);
            assertThat(rows).isEmpty();
            assertThat(doc.columns().get(0)).isEqualTo("Matrícula");
            assertThat(doc.columns().get(doc.columns().size() - 1)).isEqualTo("ID técnico");
            return new ExportFile("usuarios.csv", "text/csv", new byte[]{1});
        });

        service.export(base, ExportFormat.CSV);
    }

    private static AdminUserFilter emptyFilter(Long id) {
        return new AdminUserFilter(id, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static AdminUserResult user(Long id, String name, String cpf) {
        return new AdminUserResult(
                id, name, LocalDate.of(1990, 1, 1), name.toLowerCase().replace(" ", "") + "@example.com",
                cpf, null, null, null, UserStatus.ACTIVE, Instant.parse("2026-01-01T12:00:00Z"), null, null
        );
    }
}
