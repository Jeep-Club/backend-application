package com.jeepclub.backend.vehicles.core.application.service.export;

import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.shared.export.ExportDocument;
import com.jeepclub.backend.shared.export.ExportException;
import com.jeepclub.backend.shared.export.ExportFile;
import com.jeepclub.backend.shared.export.ExportFormat;
import com.jeepclub.backend.shared.export.ExportRenderer;
import com.jeepclub.backend.shared.export.ExportRow;
import com.jeepclub.backend.vehicles.core.repository.VehicleExportQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleExportServiceTest {

    @Mock
    private VehicleExportQuery query;
    @Mock
    private ExportRenderer renderer;
    @Mock
    private UserQuery users;

    @InjectMocks
    private VehicleExportService service;

    @Test
    void rejectsAmbiguousScalarAndListCombinations() {
        assertThatThrownBy(() -> service.export(ExportFormat.CSV, 1L, List.of(1L), null, null, null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);

        assertThatThrownBy(() -> service.export(ExportFormat.CSV, null, null, 2L, List.of(2L), null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);
    }

    @Test
    void rejectsEmptyOversizedOrNonPositiveLists() {
        List<Long> oversized = LongStream.rangeClosed(1, VehicleExportService.MAX_IDS + 1L).boxed().toList();

        assertThatThrownBy(() -> service.export(ExportFormat.CSV, null, List.of(), null, null, null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);

        assertThatThrownBy(() -> service.export(ExportFormat.CSV, null, null, null, oversized, null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);

        assertThatThrownBy(() -> service.export(ExportFormat.CSV, null, List.of(1L, 0L), null, null, null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.INVALID_FILTER);
    }

    @Test
    void rejectsWhenAnySelectedVehicleOrOwnerDoesNotExist() {
        when(users.findByIds(List.of(10L, 20L))).thenReturn(List.of(user(10L, "Titular", "52998224725")));
        assertThatThrownBy(() -> service.export(ExportFormat.CSV, null, null, null, List.of(10L, 20L), null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.NOT_FOUND);

        when(query.countByIds(List.of(1L, 2L))).thenReturn(1L);
        assertThatThrownBy(() -> service.export(ExportFormat.CSV, null, List.of(1L, 2L), null, null, null, null, null, null, null))
                .isInstanceOf(ExportException.class)
                .extracting(ex -> ((ExportException) ex).reason())
                .isEqualTo(ExportException.Reason.NOT_FOUND);
    }

    @Test
    void deduplicatesListsAppliesFilterLabelsAndPropagatesTowing() {
        when(users.findByIds(List.of(10L, 20L))).thenReturn(List.of(
                user(10L, "Ana", "52998224725"),
                user(20L, "Bruno", "11144477735")
        ));
        when(query.countByIds(List.of(1L, 2L))).thenReturn(2L);
        when(query.read(List.of(1L, 2L), List.of(10L, 20L), true, null, null, null, null, 0))
                .thenReturn(List.of(new VehicleExportQuery.Entry(10L, ExportRow.of(1L, "Jeep", true, "ABC1D23", "123", "Jeep", "Wrangler", 2022, 2023, "Verde", 5, "DIESEL", 2.0, "ACTIVE", true, 10L, null, null))));
        when(renderer.render(any(ExportDocument.class), eq(ExportFormat.PDF))).thenAnswer(inv -> {
            ExportDocument doc = inv.getArgument(0);
            List<ExportRow> rows = new ArrayList<>();
            doc.rows().read(rows::add);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).group()).isNull();
            return new ExportFile("veiculos.pdf", "application/pdf", new byte[]{1});
        });

        service.export(ExportFormat.PDF, null, List.of(1L, 2L, 1L), null, List.of(10L, 20L, 10L), true, null, null, null, null);

        ArgumentCaptor<ExportDocument> captor = ArgumentCaptor.forClass(ExportDocument.class);
        verify(renderer).render(captor.capture(), eq(ExportFormat.PDF));
        assertThat(captor.getValue().filters())
                .containsExactly("IDs: 1, 2", "Titulares: 10, 20", "Possui reboque: Sim");
        verify(query).read(List.of(1L, 2L), List.of(10L, 20L), true, null, null, null, null, 0);
    }

    @Test
    void singleOwnerViaOwnerIdsGroupsPdfRows() {
        when(users.existsById(10L)).thenReturn(true);
        when(users.findByIds(List.of(10L))).thenReturn(List.of(user(10L, "Ana", "52998224725")));
        when(query.read(null, List.of(10L), false, null, null, null, null, 0))
                .thenReturn(List.of(new VehicleExportQuery.Entry(10L, ExportRow.of(1L, "Jeep", false, "ABC1D23", "123", "Jeep", "Renegade", 2020, 2021, "Preto", 5, "FLEX", 1.8, "ACTIVE", false, 10L, null, null))));
        when(renderer.render(any(ExportDocument.class), eq(ExportFormat.PDF))).thenAnswer(inv -> {
            ExportDocument doc = inv.getArgument(0);
            List<ExportRow> rows = new ArrayList<>();
            doc.rows().read(rows::add);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).group()).isEqualTo("Titular: Ana | ID: 10 | CPF: 52998224725");
            return new ExportFile("veiculos.pdf", "application/pdf", new byte[]{1});
        });

        service.export(ExportFormat.PDF, null, null, null, List.of(10L, 10L), false, null, null, null, null);
    }

    private static UserDetails user(Long id, String name, String cpf) {
        return new UserDetails(id, name, null, null, cpf, null, null, null, true, null, null, null, UserStatus.ACTIVE, null);
    }
}
