package com.jeepclub.backend.iam.identity.infra.storage;

import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.storage.api.module.StorageConsumerPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProfileStoragePolicyTest {
    private final UserQuery users = mock(UserQuery.class);
    private final ProfileStoragePolicy policy = new ProfileStoragePolicy(users);
    private final StorageConsumerPolicy.Reference own = new StorageConsumerPolicy.Reference(StorageConsumerPolicy.Context.IDENTITY_PROFILE, "11", "PHOTO");

    @Test void activeOwnerIsAuthorizedOnlyForOwnProfileAndOwnedAsset() {
        when(users.isAdministrativelyActive(11L)).thenReturn(true);
        assertThat(policy.mayUpload(11, "11")).isTrue();
        assertThat(policy.mayRead(11, own, true)).isTrue();
        assertThat(policy.mayAssociate(11, new StorageConsumerPolicy.Asset(11L), "11", "PHOTO")).isTrue();
        assertThat(policy.mayAssociate(11, new StorageConsumerPolicy.Asset(null), "11", "PHOTO")).isFalse();
        assertThat(policy.mayAssociate(11, new StorageConsumerPolicy.Asset(12L), "11", "PHOTO")).isFalse();
        assertThat(policy.mayAssociate(11, new StorageConsumerPolicy.Asset(11L), "11", "COVER")).isFalse();
        assertThat(policy.mayRead(12, own, false)).isFalse();
        assertThat(policy.mayRead(11, new StorageConsumerPolicy.Reference(StorageConsumerPolicy.Context.VEHICLE_PHOTO, "11", "PHOTO"), false)).isFalse();
        assertThat(policy.uploadIdentity(11, "11")).isEqualTo(new StorageConsumerPolicy.UploadIdentity(11L, StorageConsumerPolicy.SubjectType.USER, 11L));
    }
    @Test void inactiveUserCannotUploadAssociateOrRead() {
        assertThat(policy.mayUpload(11, "11")).isFalse();
        assertThat(policy.mayRead(11, own, false)).isFalse();
        assertThat(policy.mayAssociate(11, new StorageConsumerPolicy.Asset(11L), "11", "PHOTO")).isFalse();
        assertThatThrownBy(() -> policy.uploadIdentity(11, "11")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
