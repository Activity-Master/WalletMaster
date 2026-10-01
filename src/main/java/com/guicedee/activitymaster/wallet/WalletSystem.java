package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.client.services.administration.MasterDefaultPlugin;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

/** Registers the Wallet Master capability; FSDM Arrangements and Events remain canonical. */
public final class WalletSystem extends MasterDefaultPlugin<WalletSystem> {
    public static final String NAME = "Wallet Master";
    public static final String WALLET_ARRANGEMENT_TYPE = "Wallet";
    public static final String CLEARING_ARRANGEMENT_TYPE = "Wallet Clearing";
    public static final String TRANSACTION_EVENT_TYPE = "Transaction Event";
    @Override public String getSystemName() { return NAME; }
    @Override public String getSystemDescription() { return "Wallet arrangements and event-linked transactions"; }
    @Override public int totalTasks() { return 0; }
    @Override public Uni<Void> createDefaults(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return Uni.createFrom().voidItem();
    }
    @Override public Uni<Void> postStartup(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return super.postStartup(session, enterprise);
    }
}
