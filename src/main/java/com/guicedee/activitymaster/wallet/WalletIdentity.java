package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import java.util.Objects;
import java.util.UUID;

/** Server-resolved identity. Never deserialize this from REST or GraphQL input.
 * identityToken is the ActivityMaster identifying credential, not a SecurityToken row primary key. */
public record WalletIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context, UUID identityToken,
                             String providerId, UUID installationPartyId) {
    public WalletIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context, UUID identityToken, String providerId) {
        this(partyId, enterpriseId, context, identityToken, providerId, partyId);
    }
    public WalletIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context, UUID identityToken) {
        this(partyId, enterpriseId, context, identityToken, "wallet");
    }
    public WalletIdentity {
        Objects.requireNonNull(partyId); Objects.requireNonNull(enterpriseId);
        Objects.requireNonNull(context); Objects.requireNonNull(identityToken);
        Objects.requireNonNull(installationPartyId);
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Wallet provider required");
        if (context.realm() == ActivityScope.Realm.WORK && !enterpriseId.equals(context.ownerId()))
            throw new SecurityException("Wallet enterprise scope mismatch");
        if (context.realm() != ActivityScope.Realm.WORK && !partyId.equals(context.ownerId()))
            throw new SecurityException("Wallet party scope mismatch");
    }
    public UUID[] tokens() { return new UUID[]{identityToken}; }
    public ActivityScope.Actor actor() { return new ActivityScope.Actor(partyId, true); }
}
