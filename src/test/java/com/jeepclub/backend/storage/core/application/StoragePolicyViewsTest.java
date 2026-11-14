package com.jeepclub.backend.storage.core.application;

import com.jeepclub.backend.storage.api.module.StorageConsumerPolicy;
import com.jeepclub.backend.storage.core.domain.enums.MediaContext;
import com.jeepclub.backend.storage.core.domain.enums.MediaSubjectType;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class StoragePolicyViewsTest {
    @Test void publicAndInternalEnumsRemainCompatible() {
        assertThat(Arrays.stream(StorageConsumerPolicy.Context.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(Arrays.stream(MediaContext.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(StorageConsumerPolicy.SubjectType.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(Arrays.stream(MediaSubjectType.values()).map(Enum::name).toList());
        for (var context : MediaContext.values()) {
            assertThat(StorageConsumerPolicy.Context.valueOf(context.name()).name()).isEqualTo(context.name());
        }
        for (var subject : StorageConsumerPolicy.SubjectType.values()) {
            assertThat(MediaSubjectType.valueOf(subject.name()).name()).isEqualTo(subject.name());
        }
    }
}
