package com.guicedee.activitymaster.wallet;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import static com.guicedee.activitymaster.wallet.WalletModels.*;

/** Shared transport entry point. Captures host identity, then awaits the complete DB transaction. */
public class WalletApi {
    private final WalletIdentityProvider identities;
    private final IWalletService<?> service;
    @Inject public WalletApi(WalletIdentityProvider identities, IWalletService<?> service) {
        this.identities=identities; this.service=service;
    }
    @FunctionalInterface private interface Work<T> {
        Uni<T> run(org.hibernate.reactive.mutiny.Mutiny.StatelessSession session,
                   com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems<?, ?> system,
                   WalletIdentity identity);
    }
    private <T> Uni<T> execute(String enterprise, Work<T> work) {
        if (enterprise==null || enterprise.isBlank()) return Uni.createFrom().failure(new IllegalArgumentException("Enterprise required"));
        return Uni.createFrom().deferred(identities::current)
                .onItem().ifNull().failWith(() -> new SecurityException("Authenticated wallet identity required"))
                .chain(identity -> SessionUtils.withActivityMaster(enterprise,WalletSystem.NAME, tuple -> {
                    if (!identity.enterpriseId().equals(tuple.getItem2().getId()))
                        return Uni.createFrom().failure(new SecurityException("Wallet enterprise scope mismatch"));
                    // System tokens authorize internal provisioning only; user permission checks use identity.tokens().
                    return work.run(tuple.getItem1(),tuple.getItem3(),identity);
                }));
    }
    public Uni<Wallet> create(String enterprise, Create request) {
        return execute(enterprise,(session,system,identity) -> service.create(session,system,identity,request));
    }
    public Uni<Balance> balance(String enterprise, UUID walletId, String unit) {
        return execute(enterprise,(session,system,identity) -> service.balance(session,system,identity,walletId,unit));
    }
    public Uni<List<HistoryLine>> history(String enterprise, UUID walletId, String unit, int offset, int limit) {
        return execute(enterprise,(session,system,identity) -> service.history(session,system,identity,walletId,unit,offset,limit));
    }
    public Uni<Receipt> move(String enterprise, Action action, Movement request) {
        return execute(enterprise,(session,system,identity) -> service.move(session,system,identity,action,request));
    }
}
