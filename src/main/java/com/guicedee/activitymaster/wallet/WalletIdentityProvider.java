package com.guicedee.activitymaster.wallet;

import com.google.inject.ImplementedBy;
import io.smallrye.mutiny.Uni;

/** Bind in the host to its authenticated request context; invoked once per API call. */
@ImplementedBy(WalletIdentityProvider.Deny.class)
public interface WalletIdentityProvider {
    Uni<WalletIdentity> current();

    final class Deny implements WalletIdentityProvider {
        public Uni<WalletIdentity> current() {
            return Uni.createFrom().failure(new SecurityException("Authenticated wallet identity required"));
        }
    }
}
