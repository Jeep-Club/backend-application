package com.jeepclub.backend.storage.core.application;

import com.jeepclub.backend.storage.api.module.StorageConsumerPolicy;
import com.jeepclub.backend.storage.core.domain.enums.MediaContext;
import com.jeepclub.backend.storage.core.domain.model.MediaAsset;
import com.jeepclub.backend.storage.core.domain.model.MediaReference;

/** Maps internal state to the minimum information required by a consumer policy. */
final class StoragePolicyViews {
    private StoragePolicyViews() {}
    static boolean matches(StorageConsumerPolicy policy, MediaContext context) {
        return policy.context() != null && context != null && policy.context().name().equals(context.name());
    }
    static StorageConsumerPolicy.Asset asset(MediaAsset value) {
        return new StorageConsumerPolicy.Asset(value.ownerUserId());
    }
    static StorageConsumerPolicy.Reference reference(MediaReference value) {
        return new StorageConsumerPolicy.Reference(StorageConsumerPolicy.Context.valueOf(value.context().name()), value.consumerId(), value.role());
    }
}
