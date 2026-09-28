package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.List;
import java.util.UUID;
import static com.guicedee.activitymaster.wallet.WalletModels.*;

/** All work uses the supplied ActivityMaster stateless transaction. */
public interface IWalletService<J extends IWalletService<J>> {
    Uni<Wallet> create(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Create request);
    Uni<Balance> balance(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, UUID walletId, String unit);
    Uni<List<HistoryLine>> history(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity,
                                 UUID walletId, String unit, int offset, int limit);
    Uni<Receipt> move(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Action action, Movement request);
}
