package com.jeepclub.backend.storage.infra.persistence.adapter;

import com.jeepclub.backend.storage.core.repository.UploadAdmission;
import com.jeepclub.backend.storage.infra.persistence.entity.*;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Storage custody budgets, not a generic request limiter. Unknown/crashed reservations remain charged for review. */
@Repository
public class SqlUploadAdmission implements UploadAdmission {
    private static final long MAX_RESERVATION=20L*1024*1024;
    private final EntityManager em;private final TransactionTemplate tx;
    private final long userBytes,globalBytes,maxUploads;private final int hourly,concurrent;
    public SqlUploadAdmission(EntityManager em,PlatformTransactionManager manager,
        @Value("${storage.upload.max-user-bytes:536870912}") long userBytes,
        @Value("${storage.upload.max-global-bytes:10737418240}") long globalBytes,
        @Value("${storage.upload.max-hourly-attempts:30}") int hourly,
        @Value("${storage.upload.max-concurrent-per-user:2}") int concurrent,
        @Value("${storage.upload.max-retained-uploads-per-user:1000}") long maxUploads) {
        if(userBytes<MAX_RESERVATION || globalBytes<MAX_RESERVATION || hourly<1 || concurrent<1 || concurrent>10 || maxUploads<1) throw new IllegalArgumentException("Invalid upload budget");
        this.em=em;this.userBytes=userBytes;this.globalBytes=globalBytes;this.hourly=hourly;this.concurrent=concurrent;this.maxUploads=maxUploads;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    private void ensure(long scope) {
        try {tx.executeWithoutResult(status -> {
            boundCatalogTransaction();
            if(em.find(UploadBudgetEntity.class,scope)!=null) return;
            // Existing permits are a different accounting ledger from blobs/assets.
            // Reconstructing a missing budget from those sources could undercount custody.
            Long permits=scope==0 ? em.createQuery("select count(p) from UploadPermitEntity p",Long.class).getSingleResult()
                : em.createQuery("select count(p) from UploadPermitEntity p where p.actorUserId=:actor",Long.class)
                    .setParameter("actor",scope).getSingleResult();
            if(permits>0) throw new IllegalStateException("Upload budget requires reconciliation");
            var row=new UploadBudgetEntity();row.setScopeId(scope);row.setWindowStart(Instant.now());
            Long existing=scope==0?em.createQuery("select coalesce(sum(b.sizeBytes),0) from StoredBlobEntity b",Long.class).getSingleResult():
                em.createQuery("select coalesce(sum(v.sizeBytes),0) from MediaVariantEntity v,MediaAssetEntity a where v.mediaId=a.mediaId and a.uploadedByUserId=:actor",Long.class).setParameter("actor",scope).getSingleResult();
            row.setUsedBytes(existing);if(scope!=0) row.setChargedUploads(em.createQuery("select count(a) from MediaAssetEntity a where a.uploadedByUserId=:actor",Long.class).setParameter("actor",scope).getSingleResult());
            em.persist(row);em.flush();
        });} catch(RuntimeException collision) {
            try {
                if (!Boolean.TRUE.equals(tx.execute(s -> {
                    boundCatalogTransaction();
                    return em.find(UploadBudgetEntity.class,scope)!=null;
                }))) throw collision;
            } catch (RuntimeException verification) {
                if (verification != collision) collision.addSuppressed(verification);
                throw collision;
            }
        }
    }
    private UploadBudgetEntity lock(long scope) {
        var row=em.find(UploadBudgetEntity.class,scope,LockModeType.PESSIMISTIC_WRITE);
        // A request-scoped persistence context can already contain an older version.
        // Locking alone need not refresh managed state after another transaction commits.
        if(row!=null) em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
        return row;
    }
    private UploadPermitEntity lockPermit(String id) {
        var row=em.find(UploadPermitEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(row!=null) em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
        return row;
    }
    @Override public Permit reserve(long actor) {
        return reserve(actor, null).permit();
    }
    @Override public KeyedReservation reserve(long actor, RequestIdentity identity) {
        if(actor<=0) throw new IllegalArgumentException("Invalid actor");ensure(0);ensure(actor);
        // A transaction that rolls back must never share its execution identity with a later retry.
        var permit = new Permit(UUID.randomUUID().toString(), actor, UUID.randomUUID().toString());
        var provisional = new AtomicReference<>(permit);
        try {
            return tx.execute(status -> {
                boundCatalogTransaction();
                var global=lock(0);var user=lock(actor);Instant now=Instant.now();
                UploadPermitEntity prior = identity == null ? null : findRequest(actor, identity);
                if (prior != null) {
                    if (!identity.fingerprint().equals(prior.getRequestFingerprint())) throw new PayloadConflict();
                    if (!canReadmit(prior)) return replay(prior);
                }
                if(!now.isBefore(user.getWindowStart().plusSeconds(3600))) {user.setWindowStart(now);user.setWindowAttempts(0);}
                if(user.getWindowAttempts()>=hourly || user.getInFlight()>=concurrent || user.getChargedUploads()>=maxUploads
                    || user.getUsedBytes()>userBytes-MAX_RESERVATION || global.getUsedBytes()>globalBytes-MAX_RESERVATION) throw new LimitExceeded();
                user.setWindowAttempts(user.getWindowAttempts()+1);user.setInFlight(user.getInFlight()+1);
                user.setUsedBytes(user.getUsedBytes()+MAX_RESERVATION);global.setUsedBytes(global.getUsedBytes()+MAX_RESERVATION);
                if (prior != null) {
                    // Only a completed permit with its reservation or charge safely reversed may restart.
                    var retry = new Permit(prior.getId(), actor, permit.executionId());
                    provisional.set(retry);
                    prior.setExecutionId(retry.executionId());
                    prior.setCompleted(false);
                    prior.setAccountedBytes(MAX_RESERVATION);
                    em.flush();
                    return new KeyedReservation(retry, null);
                }
                var row=new UploadPermitEntity();row.setId(permit.id());row.setActorUserId(actor);row.setAccountedBytes(MAX_RESERVATION);row.setCreatedAt(now);
                row.setExecutionId(permit.executionId());
                if (identity != null) {
                    row.setRequestScopeHash(identity.scopeHash());
                    row.setRequestKeyHash(identity.keyHash());
                    row.setRequestFingerprint(identity.fingerprint());
                }
                em.persist(row);em.flush();
                return new KeyedReservation(permit, null);
            });
        } catch (TransactionSystemException | DataAccessResourceFailureException | org.springframework.orm.jpa.JpaSystemException failure) {
            // The permit never reached the upload pipeline, so it could not start a physical write.
            try {
                abortBeforeStore(provisional.get(), true);
            } catch (RuntimeException recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
    }
    private UploadPermitEntity findRequest(long actor, RequestIdentity identity) {
        var rows = em.createQuery("""
                select p from UploadPermitEntity p
                where p.actorUserId=:actor and p.requestScopeHash=:scope and p.requestKeyHash=:key
                """, UploadPermitEntity.class)
                .setParameter("actor", actor)
                .setParameter("scope", identity.scopeHash())
                .setParameter("key", identity.keyHash())
                .getResultList();
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        em.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        return row;
    }
    private boolean canReadmit(UploadPermitEntity row) {
        return row.isCompleted() && !row.isCharged() && row.getAccountedBytes() == 0 && row.getCatalogAssetId() == null;
    }
    private KeyedReservation replay(UploadPermitEntity row) {
        if (!row.isCompleted() || !row.isCharged() || row.getCatalogAssetId() == null) {
            throw new IllegalStateException("Upload transaction outcome unavailable");
        }
        return new KeyedReservation(null, row.getCatalogAssetId());
    }
    @Override public void charge(Permit permit,long bytes) {
        if(bytes<=0 || bytes>MAX_RESERVATION) throw new IllegalArgumentException("Invalid representation budget");
        tx.executeWithoutResult(status -> {
            boundCatalogTransaction();
            var global=lock(0);var user=lock(permit.actor());var row=lockPermit(permit.id());
            requireCurrentExecution(row, permit);
            if(row.isCompleted() || row.isCharged()) throw new IllegalArgumentException("Invalid upload permit");
            if(user.getChargedUploads()>=maxUploads) throw new LimitExceeded();
            long difference=bytes-row.getAccountedBytes();global.setUsedBytes(global.getUsedBytes()+difference);user.setUsedBytes(user.getUsedBytes()+difference);
            user.setChargedUploads(user.getChargedUploads()+1);row.setAccountedBytes(bytes);row.setCharged(true);em.flush();
        });
    }
    @Override public void recordPlannedBlobs(Permit permit,java.util.List<PlannedBlob> blobs) {
        if(blobs==null || blobs.isEmpty()) throw new IllegalArgumentException("Missing planned blobs");
        tx.executeWithoutResult(status -> {
            boundCatalogTransaction();
            var row=lockPermit(permit.id());
            requireCurrentExecution(row, permit);
            if(row.isCompleted() || !row.isCharged()) throw new IllegalArgumentException("Invalid upload permit");
            long total=0;
            for(var blob:blobs) {
                if(blob==null || blob.sha256()==null || !blob.sha256().matches("[0-9a-f]{64}") || blob.sizeBytes()<=0) throw new IllegalArgumentException("Invalid planned blob");
                total=Math.addExact(total,blob.sizeBytes());
            }
            if(total!=row.getAccountedBytes()) throw new IllegalArgumentException("Representation budget mismatch");
            if (row.getRequestKeyHash() != null && reuseIntents(permit, blobs)) return;
            for(int index=0;index<blobs.size();index++) {
                var blob=blobs.get(index);var planned=new UploadPlannedBlobEntity();
                planned.setPermitId(permit.id());planned.setPosition(index);planned.setSha256(blob.sha256());planned.setSizeBytes(blob.sizeBytes());em.persist(planned);
            }
            em.flush();
        });
    }
    private boolean reuseIntents(Permit permit, java.util.List<PlannedBlob> blobs) {
        var previous = em.createQuery("""
                select p from UploadPlannedBlobEntity p where p.permitId=:permit order by p.position
                """, UploadPlannedBlobEntity.class)
                .setParameter("permit", permit.id())
                .getResultList();
        if (previous.isEmpty()) return false;
        if (previous.size() != blobs.size()) throw new IllegalStateException("Upload representations changed before retry");
        for (int index = 0; index < blobs.size(); index++) {
            var stored = previous.get(index);
            var requested = blobs.get(index);
            if (stored.getPosition() != index || stored.getSizeBytes() != requested.sizeBytes()
                    || !stored.getSha256().equals(requested.sha256())) {
                throw new IllegalStateException("Upload representations changed before retry");
            }
        }
        return true;
    }
    @Override public void abortBeforeStore(Permit permit) {
        abortBeforeStore(permit, false);
    }
    private void abortBeforeStore(Permit permit, boolean recoveringReservation) {
        tx.executeWithoutResult(status -> {
            boundCatalogTransaction();
            var global=lock(0);var user=lock(permit.actor());var row=lockPermit(permit.id());
            // The same budget locks serialize this check with the original reservation.
            if (row == null && recoveringReservation) return;
            if(row==null || row.getActorUserId()!=permit.actor()) throw new IllegalArgumentException("Invalid upload permit");
            if(!isCurrentExecution(row, permit) || row.isCompleted()) return;
            if (recoveringReservation && (row.isCharged() || row.getAccountedBytes() != MAX_RESERVATION)) {
                throw new IllegalStateException("Unexpected reservation state");
            }
            global.setUsedBytes(global.getUsedBytes()-row.getAccountedBytes());
            user.setUsedBytes(user.getUsedBytes()-row.getAccountedBytes());
            if(row.isCharged()) {
                user.setChargedUploads(user.getChargedUploads()-1);
                // Preserve the historical flag for legacy callers. A keyed permit can restart
                // only after this explicitly pre-storage compensation is persisted.
                if (row.getRequestKeyHash() != null) row.setCharged(false);
            }
            user.setInFlight(user.getInFlight()-1);row.setAccountedBytes(0);row.setCompleted(true);em.flush();
        });
    }
    @Override public void finish(Permit permit) {
        tx.executeWithoutResult(status -> {
            boundCatalogTransaction();
            finishLocked(permit);
        });
    }
    @Override public void boundCatalogTransaction() {
        StorageJdbcLimits.boundMySqlNetwork(em);
    }
    @Override @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void finishInCatalogTransaction(Permit permit,long mediaId) {
        if(mediaId<=0) throw new IllegalArgumentException("Invalid media asset");
        var global = lock(0);
        var user = lock(permit.actor());
        var row = lockPermit(permit.id());
        requireCurrentExecution(row, permit);
        if (row.isCompleted() && java.util.Objects.equals(row.getCatalogAssetId(), mediaId)) return;
        if (row.isCompleted() || !row.isCharged() || row.getCatalogAssetId() != null) {
            throw new IllegalStateException("Upload permit cannot be bound");
        }
        row.setCatalogAssetId(mediaId);
        finishLocked(global, user, row);
    }
    private void finishLocked(Permit permit) {
            var global=lock(0);var user=lock(permit.actor());var row=lockPermit(permit.id());
            requireCurrentExecution(row, permit);
            finishLocked(global, user, row);
    }
    private void finishLocked(UploadBudgetEntity global, UploadBudgetEntity user, UploadPermitEntity row) {
            if(row.isCompleted()) return;
            if(!row.isCharged()){global.setUsedBytes(global.getUsedBytes()-row.getAccountedBytes());user.setUsedBytes(user.getUsedBytes()-row.getAccountedBytes());row.setAccountedBytes(0);}
            user.setInFlight(user.getInFlight()-1);row.setCompleted(true);em.flush();
    }
    private static boolean isCurrentExecution(UploadPermitEntity row, Permit permit) {
        return permit.executionId() != null && permit.executionId().equals(row.getExecutionId());
    }
    private static void requireCurrentExecution(UploadPermitEntity row, Permit permit) {
        if (row == null || row.getActorUserId() != permit.actor()) throw new IllegalArgumentException("Invalid upload permit");
        if (!isCurrentExecution(row, permit)) throw new IllegalStateException("Stale upload execution");
    }
}
