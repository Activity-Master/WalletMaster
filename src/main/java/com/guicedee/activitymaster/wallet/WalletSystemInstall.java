package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.client.services.IArrangementsService;
import com.guicedee.activitymaster.fsdm.client.services.IEventService;
import com.guicedee.activitymaster.fsdm.client.services.IClassificationService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.classifications.IClassification;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;
import com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate;
import com.guicedee.activitymaster.fsdm.client.services.systems.SortedUpdate;
import com.guicedee.activitymaster.fsdm.transactions.TransactionService;
import com.guicedee.activitymaster.fsdm.transactions.FsdmBehaviorTaxonomy;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.UUID;

/** Installs the wallet taxonomy for each enterprise using the existing FSDM model. */
@SortedUpdate(sortOrder = 1200, taskCount = 5)
public final class WalletSystemInstall implements ISystemUpdate {
    public WalletSystemInstall() { }

    @Override
    public Uni<Boolean> update(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        WalletSystem wallet = IGuiceContext.get(WalletSystem.class);
        IArrangementsService<?> arrangements = IGuiceContext.get(IArrangementsService.class);
        IEventService<?> events = IGuiceContext.get(IEventService.class);
        IClassificationService<?> classifications = IGuiceContext.get(IClassificationService.class);
        return wallet.getSystem(session, enterprise)
                .chain(system -> wallet.getSystemToken(session, enterprise)
                        .chain(token -> arrangements.createArrangementType(session,
                                        WalletSystem.WALLET_ARRANGEMENT_TYPE, system, token)
                                .chain(() -> arrangements.createArrangementType(session,
                                        WalletSystem.CLEARING_ARRANGEMENT_TYPE, system, token))
                                .chain(() -> events.createEventType(session,
                                        WalletSystem.TRANSACTION_EVENT_TYPE, system, token))
                                .chain(() -> FsdmBehaviorTaxonomy.ensure(session, system, token))
                                .chain(() -> seedClassifications(session, classifications, system, token))
                                .chain(() -> new TransactionService("wallet", system.getId())
                                        .ensureType(session, enterprise, system, "debit", (short) -1, token))
                                .chain(() -> new TransactionService("wallet", system.getId())
                                        .ensureType(session, enterprise, system, "credit", (short) 1, token))))
                .replaceWith(Boolean.TRUE);
    }

    private Uni<Void> seedClassifications(Mutiny.StatelessSession session,
                                           IClassificationService<?> classifications,
                                           ISystems<?, ?> system, UUID token) {
        return classifications.create(session, "Wallet", "Wallet transaction taxonomy",
                        EnterpriseClassificationDataConcepts.GlobalClassificationsDataConceptName,
                        system, token)
                .chain(root -> seedActions(session, classifications, system, root, token)
                        .chain(() -> seedMetrics(session, classifications, system, root, token))
                        .chain(() -> classifications.create(session, "WalletTransactionType",
                                "Classifies a Transaction-to-TransactionType relationship",
                                EnterpriseClassificationDataConcepts.TransactionXTransactionType,
                                system, root, token))
                        .chain(() -> seedRelationshipRoles(session, classifications, system, root, token)))
                .replaceWithVoid();
    }

    private Uni<Void> seedRelationshipRoles(Mutiny.StatelessSession session,
                                            IClassificationService<?> classifications,
                                            ISystems<?, ?> system, IClassification<?, ?> root, UUID token) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        chain = chain.chain(() -> classifications.create(session, "PostingActor", "PostingActor transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXInvolvedParty, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "Payer", "Payer transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXInvolvedParty, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "Payee", "Payee transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXInvolvedParty, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "Cashier", "Cashier transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXInvolvedParty, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "PointOfSaleDevice", "PointOfSaleDevice transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXResourceItem, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "AccountingArrangement", "AccountingArrangement transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXArrangement, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "PurchaseAgreement", "PurchaseAgreement transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXArrangement, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "ParentEvent", "ParentEvent transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXEvent, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "PurchasedProduct", "PurchasedProduct transaction relationship",
                EnterpriseClassificationDataConcepts.TransactionXProduct, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "WalletOwner", "WalletOwner wallet relationship",
                EnterpriseClassificationDataConcepts.ArrangementXInvolvedParty, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "WalletAccountType", "WalletAccountType wallet relationship",
                EnterpriseClassificationDataConcepts.ArrangementXArrangementType, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "WalletEventType", "WalletEventType wallet relationship",
                EnterpriseClassificationDataConcepts.EventXEventType, system, root, token).replaceWithVoid());
        chain = chain.chain(() -> classifications.create(session, "WalletMovement", "WalletMovement wallet relationship",
                EnterpriseClassificationDataConcepts.EventXArrangement, system, root, token).replaceWithVoid());
        return chain;
    }

    private Uni<Void> seedActions(Mutiny.StatelessSession session, IClassificationService<?> classifications,
                                  ISystems<?, ?> system, IClassification<?, ?> root, UUID token) {
        return classifications.create(session, "WalletAction", "Business action for a transaction Event",
                        EnterpriseClassificationDataConcepts.EventXClassification, system, root, token)
                .chain(action -> classifications.create(session, "WalletTransfer", "Funds moved between wallets",
                                EnterpriseClassificationDataConcepts.EventXClassification, system, action, token)
                        .chain(() -> classifications.create(session, "WalletDeposit", "Funds added from a clearing arrangement",
                                EnterpriseClassificationDataConcepts.EventXClassification, system, action, token))
                        .chain(() -> classifications.create(session, "WalletWithdrawal", "Funds sent to a clearing arrangement",
                                EnterpriseClassificationDataConcepts.EventXClassification, system, action, token))
                        .chain(() -> classifications.create(session, "WalletReversal", "Compensating transaction Event",
                                EnterpriseClassificationDataConcepts.EventXClassification, system, action, token)))
                .replaceWithVoid();
    }

    private Uni<Void> seedMetrics(Mutiny.StatelessSession session, IClassificationService<?> classifications,
                                  ISystems<?, ?> system, IClassification<?, ?> root, UUID token) {
        return classifications.create(session, "WalletMetric", "Derived arrangement transaction metrics",
                        EnterpriseClassificationDataConcepts.ArrangementXClassification, system, root, token)
                .chain(metric -> classifications.create(session, "WalletBalance", "Balance projection by unit",
                                EnterpriseClassificationDataConcepts.ArrangementXClassification, system, metric, token)
                        .chain(() -> classifications.create(session, "WalletTransactionCount", "Posted transaction count projection",
                                EnterpriseClassificationDataConcepts.ArrangementXClassification, system, metric, token)))
                .replaceWithVoid();
    }

}
