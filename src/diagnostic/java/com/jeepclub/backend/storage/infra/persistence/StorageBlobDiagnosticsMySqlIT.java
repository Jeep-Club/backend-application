package com.jeepclub.backend.storage.infra.persistence;

class StorageBlobDiagnosticsMySqlIT extends StorageBlobMySqlIT {
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="storage.admin.benchmark",matches="true")
    void benchmarkAdministrativeCombinedFiltersOnSyntheticCatalog() {
        long actor=990099L;var records=new java.util.ArrayList<Object[]>();var fixtureTime=java.sql.Timestamp.from(java.time.Instant.now());
        for(int i=0;i<5000;i++) records.add(new Object[]{"BENCH_"+i,fixtureTime,fixtureTime});
        jdbc.batchUpdate("insert into storage_media_assets (origin,source_context,source_record_id,uploaded_by_user_id,owner_user_id,subject_type,purpose,classification,physical_state,created_at,updated_at,version) values ('UPLOAD','IDENTITY_PROFILE',?,990099,990099,'UNKNOWN','PHOTO','CLASSIFIED','PRESENT',?,?,0)",records);
        jdbc.update("insert into storage_media_control (media_id,received_sha256,state,retention_sensitive,legal_hold,discarded,version) select id,?,'VISIBLE',false,false,false,0 from storage_media_assets where uploaded_by_user_id=?","a".repeat(64),actor);
        jdbc.update("insert into storage_media_variants (media_id,variant_code,role,provider,namespace,storage_key,mime_type,size_bytes,created_at) select id,'MASTER','MASTER','LOCAL','images',concat('images/bench-',id,'.png'),if(mod(id,2)=0,'image/png','image/jpeg'),65536,CURRENT_TIMESTAMP from storage_media_assets where uploaded_by_user_id=?",actor);
        jdbc.update("insert into storage_media_references (media_id,context,consumer_id,role,association_origin,linked_at,unlinked_at,version) select id,'IDENTITY_PROFILE',cast(id as char),'PHOTO','UPLOAD',CURRENT_TIMESTAMP,if(mod(id,2)=0,null,CURRENT_TIMESTAMP),0 from storage_media_assets where uploaded_by_user_id=?",actor);
        var stats=quotaEntityManager.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        try {
            var times=new java.util.ArrayList<Long>();java.time.Instant now=java.time.Instant.now();long after=0;
            for(int i=0;i<25;i++) {
                if(i%5==0) after=0;
                var filter=new com.jeepclub.backend.storage.core.repository.MediaAdministration.Search(after,100,com.jeepclub.backend.storage.core.domain.enums.MediaContext.IDENTITY_PROFILE,null,actor,actor,now.minusSeconds(3600),now.plusSeconds(3600),"image/png",com.jeepclub.backend.storage.core.repository.MediaAdministration.Usage.ACTIVE,"VISIBLE",com.jeepclub.backend.storage.core.domain.enums.MediaPhysicalState.PRESENT);
                if(i==5) stats.clear();long start=System.nanoTime();var page=admin.search(filter);long micros=java.util.concurrent.TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-start);
                org.assertj.core.api.Assertions.assertThat(page).hasSize(100);
                after=page.get(page.size()-1).mediaId();if(i>=5) times.add(micros);
            }
            org.assertj.core.api.Assertions.assertThat(stats.getPrepareStatementCount()).isEqualTo(20);
            java.util.Collections.sort(times);
            System.out.println("ADMIN_SEARCH_BENCHMARK rows=5000 qualifying=2500 page=100 warmup=5 samples=20 concurrency=1 mysql=8.0.46 queries=20 median_us="+times.get(9)+" p95_us="+times.get(18)+" max_us="+times.get(19));
        } finally {stats.setStatisticsEnabled(previous);}
    }
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="storage.dedup.timing",matches="true")
    void diagnosticTimingNewAndPreviouslyStoredSyntheticBlobs() throws Exception {
        var rng=new java.util.Random(537);
        var existing=new java.util.ArrayList<byte[]>();var fresh=new java.util.ArrayList<byte[]>();
        for(int i=0;i<50;i++) {byte[] a=new byte[65536],b=new byte[65536];rng.nextBytes(a);rng.nextBytes(b);existing.add(a);fresh.add(b);blobs.store(a);}
        for(int i=0;i<10;i++) {byte[] warm=new byte[65536];rng.nextBytes(warm);blobs.store(warm);blobs.store(warm);}
        record Request(boolean reused,byte[] data) {}
        var requests=new java.util.ArrayList<Request>();
        for(int i=0;i<50;i++) {requests.add(new Request(true,existing.get(i)));requests.add(new Request(false,fresh.get(i)));}
        java.util.Collections.shuffle(requests,new java.util.Random(1234));
        var reuseTimes=new java.util.ArrayList<Long>();var freshTimes=new java.util.ArrayList<Long>();
        for(var request:requests) {long start=System.nanoTime();blobs.store(request.data());long us=java.util.concurrent.TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-start);(request.reused()?reuseTimes:freshTimes).add(us);}
        java.util.Collections.sort(reuseTimes);java.util.Collections.sort(freshTimes);
        System.out.println("BLOB_TIMING_DIAGNOSTIC n=50 bytes=65536 warmup=10 order=randomized single-thread mysql=8.0.46 new_median_us="+freshTimes.get(24)+" new_p95_us="+freshTimes.get(47)+" reused_median_us="+reuseTimes.get(24)+" reused_p95_us="+reuseTimes.get(47));
    }
}
