package com.jeepclub.backend.storage.core.application;

import com.jeepclub.backend.storage.api.module.StorageConsumerPolicy;
import com.jeepclub.backend.storage.core.domain.enums.MediaContext;
import com.jeepclub.backend.storage.core.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class MediaUploadCapacityTest {
    @Test void planFailurePreventsPhysicalWritesAndFinishesPermit() {
        var admission=mock(UploadAdmission.class);
        var permit=new UploadAdmission.Permit("fixture",1);
        when(admission.reserve(1)).thenReturn(permit);
        doThrow(new IllegalStateException("plan failed")).when(admission).recordPlannedBlobs(eq(permit),anyList());
        var processor=mock(MediaImageProcessor.class);
        when(processor.process(any(),eq(MediaContext.IDENTITY_PROFILE),any())).thenReturn(
            new MediaImageProcessor.Processed("source",List.of(new MediaImageProcessor.Representation("MASTER",new byte[]{1,2,3},"image/png",1,1))));
        var blobs=mock(BlobCatalog.class);
        var policy=new StorageConsumerPolicy() {
            public Context context() { return Context.IDENTITY_PROFILE; }
            public boolean mayUpload(long actor,String consumerId) { return true; }
            public boolean mayRead(long actor,Reference reference,boolean master) { return false; }
        };
        var service=new MediaUploadService(mock(MediaCatalogRepository.class),blobs,processor,mock(MediaGovernance.class),
            List.of(policy),mock(PlatformTransactionManager.class),new SimpleMeterRegistry(),admission);
        assertThatThrownBy(() -> service.upload(1,MediaContext.IDENTITY_PROFILE,"1",new byte[]{1}))
            .isInstanceOf(IllegalStateException.class).hasMessage("plan failed");
        verify(blobs,never()).store(any());
        verify(admission).finish(permit);
    }
    @Test void boundsPayloadReadsAcrossUsersAndReleasesCapacityAfterFailure() throws Exception {
        var admission=mock(UploadAdmission.class);
        when(admission.reserve(anyLong())).thenAnswer(invocation -> new UploadAdmission.Permit("fixture",invocation.getArgument(0)));
        var policy=new StorageConsumerPolicy() {
            public Context context() { return Context.IDENTITY_PROFILE; }
            public boolean mayUpload(long actor,String consumerId) { return true; }
            public boolean mayRead(long actor,Reference reference,boolean master) { return false; }
        };
        var service=new MediaUploadService(mock(MediaCatalogRepository.class),mock(BlobCatalog.class),
                mock(MediaImageProcessor.class),mock(MediaGovernance.class),List.of(policy),
                mock(PlatformTransactionManager.class),new SimpleMeterRegistry(),admission);
        var entered=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=new java.util.ArrayList<Future<?>>();
            for (long actor=1;actor<=2;actor++) {
                long id=actor;
                jobs.add(pool.submit(() -> assertThatThrownBy(() -> service.upload(id,MediaContext.IDENTITY_PROFILE,"x",() -> {
                    entered.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    throw new IllegalArgumentException("fixture failure");
                })).isInstanceOf(IllegalArgumentException.class)));
            }
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.upload(3,MediaContext.IDENTITY_PROFILE,"x",() -> { throw new AssertionError("read despite saturation"); }))
                    .isInstanceOf(IllegalStateException.class);
            verify(admission,never()).reserve(3);
            release.countDown();
            for(var job:jobs) job.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> service.upload(3,MediaContext.IDENTITY_PROFILE,"x",() -> { throw new IllegalArgumentException("fixture failure"); }))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(admission).reserve(3);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
