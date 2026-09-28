package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.db.entities.arrangement.*;
import com.guicedee.activitymaster.fsdm.db.entities.events.*;
import com.guicedee.activitymaster.fsdm.db.entities.involvedparty.InvolvedParty;
import com.guicedee.activitymaster.fsdm.transactions.Transaction;
import com.guicedee.activitymaster.fsdm.transactions.TransactionService;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.guicedee.activitymaster.wallet.WalletModels.*;

/** Standalone wallet operations on the canonical FSDM rows. The caller owns the transaction. */
public final class WalletService implements IWalletService<WalletService> {
    public WalletService() { }
    private TransactionService transactions(ISystems<?, ?> system, WalletIdentity identity) {
        Objects.requireNonNull(identity);
        if (!WalletSystem.NAME.equals(system.getName()) || !identity.enterpriseId().equals(system.getEnterprise().getId()))
            throw new SecurityException("Wallet system scope mismatch");
        return new TransactionService(identity.providerId(),system.getId());
    }
    private TransactionService.Call call(ISystems<?, ?> system, WalletIdentity identity) {
        return new TransactionService.Call(identity.actor(),identity.context(),new WalletAuthority(identity,system),identity.identityToken());
    }
    public Uni<Wallet> create(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Create request) {
        Objects.requireNonNull(request);
        var tx=transactions(system,identity); var call=call(system,identity);
        UUID id=key("account:"+identity.context()+":"+identity.partyId(),identity.enterpriseId(),request.operationKey());
        return tx.checkAccess(session,call,"wallet.create")
                .chain(() -> WalletRows.lock(session,id))
                .chain(() -> session.createNativeQuery("select arrangementid from arrangement.arrangement where arrangementid=:id",UUID.class)
                        .setParameter("id",id).getResultList())
                .chain(existing -> {
                    if (!existing.isEmpty()) return tx.balance(session,call,id,"POINTS").replaceWith(new Wallet(id,identity.partyId()));
                    return arrangementType(session,system,"Wallet").chain(type ->
                            WalletRows.role(session,system,"WalletAccountType","ArrangementXArrangementType").chain(typeRole ->
                                    WalletRows.role(session,system,"WalletOwner","ArrangementXInvolvedParty").chain(ownerRole -> {
                                        Arrangement row=new Arrangement().setId(id);
                                        ArrangementXArrangementType link=new ArrangementXArrangementType();
                                        link.setId(UUID.randomUUID()); link.setArrangement(row);
                                        link.setType(new ArrangementType().setId(type)); link.setClassificationID(typeRole); link.setValue("1");
                                        ArrangementXInvolvedParty owner=new ArrangementXInvolvedParty();
                                        owner.setId(UUID.randomUUID()); owner.setArrangementID(row);
                                        owner.setInvolvedPartyID(new InvolvedParty().setId(identity.partyId())); owner.setClassificationID(ownerRole); owner.setValue("1");
                                        return WalletRows.persist(session,row,system,identity)
                                                .chain(() -> WalletRows.persist(session,link,system,identity))
                                                .chain(() -> WalletRows.persist(session,owner,system,identity))
                                                .replaceWith(new Wallet(id,identity.partyId()));
                                    })));
                });
    }
    public Uni<Balance> balance(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, UUID walletId, String unit) {
        requireUnit(unit);
        return transactions(system,identity).balance(session,call(system,identity),walletId,unit)
                .map(amount -> new Balance(walletId,unit,amount.toPlainString()));
    }
    public Uni<List<HistoryLine>> history(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity,
                                        UUID walletId, String unit, int offset, int limit) {
        if (offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("Offset >= 0 and limit 1..100 required");
        return balance(session,system,identity,walletId,unit)
                .chain(() -> session.createQuery("from Transaction t where t.arrangementId=:id and t.unit=:unit order by t.warehouseCreatedTimestamp desc,t.id",Transaction.class)
                        .setParameter("id",walletId).setParameter("unit",unit).setFirstResult(offset).setMaxResults(limit).getResultList())
                .map(rows -> rows.stream().map(t -> new HistoryLine(t.getId(),t.getEventId(),t.getLineNumber(),t.getDirection(),t.getAmount().toPlainString(),t.getUnit())).toList());
    }
    public Uni<Receipt> move(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Action action, Movement request) {
        Objects.requireNonNull(action); Objects.requireNonNull(request);
        var tx=transactions(system,identity); var call=call(system,identity);
        UUID eventId=key("event",identity.enterpriseId(),request.operationKey());
        return tx.checkAccess(session,call,"wallet."+action.name().toLowerCase(java.util.Locale.ROOT))
                .chain(() -> tx.checkAccess(session,call,"wallet.post"))
                .chain(() -> WalletRows.lock(session,eventId))
                .chain(() -> checkKind(session,system,request.sourceId(),action==Action.DEPOSIT ? "Wallet Clearing" : "Wallet"))
                .chain(() -> checkKind(session,system,request.destinationId(),action==Action.WITHDRAWAL ? "Wallet Clearing" : "Wallet"))
                .chain(() -> session.createNativeQuery("select arrangementid from arrangement.arrangement where arrangementid in (:source,:destination) order by arrangementid for update",UUID.class)
                        .setParameter("source",request.sourceId()).setParameter("destination",request.destinationId()).getResultList())
                .chain(() -> call.authority().arrangement(session,call.actor(),call.context(),request.sourceId(),-1))
                .chain(() -> call.authority().arrangement(session,call.actor(),call.context(),request.destinationId(),1))
                .chain(() -> prepareEvent(session,system,identity,eventId,action,request))
                .chain(() -> transactionType(session,system,"debit"))
                .chain(debit -> transactionType(session,system,"credit").chain(credit -> tx.post(session,call,eventId,request.operationKey(),
                        List.of(new TransactionService.Line(request.sourceId(),debit,request.decimalAmount(),request.unit()),
                                new TransactionService.Line(request.destinationId(),credit,request.decimalAmount(),request.unit())))))
                .map(result -> new Receipt(result.eventId(),result.operationKey(),result.lines().stream()
                        .map(l -> new Line(l.number(),l.arrangementId(),l.transactionTypeId(),l.direction(),l.amount().toPlainString(),l.unit())).toList()));
    }
    private Uni<Void> prepareEvent(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity,
                                   UUID id, Action action, Movement request) {
        String name=switch(action) { case TRANSFER -> "WalletTransfer"; case DEPOSIT -> "WalletDeposit"; case WITHDRAWAL -> "WalletWithdrawal"; };
        return session.createNativeQuery("select eventid from event.event where eventid=:id",UUID.class).setParameter("id",id).getResultList()
                .chain(existing -> {
                    if (!existing.isEmpty()) return new WalletAuthority(identity,system).event(session,identity.actor(),identity.context(),id)
                            .chain(() -> session.createNativeQuery("select 1 from event.eventxclassification x "
                                    +"join classification.classification c on c.classificationid=x.classificationid "
                                    +"join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid "
                                    +"where x.eventid=:id and c.classificationname=:name and d.classificationdataconceptname='EventXClassification' "
                                    +"and x.enterpriseid=:enterprise and x.effectivefromdate<=statement_timestamp() and x.effectivetodate>statement_timestamp()",Integer.class)
                            .setParameter("id",id).setParameter("name",name).setParameter("enterprise",identity.enterpriseId()).getResultList())
                            .chain(rows -> rows.isEmpty() ? Uni.createFrom().failure(new IllegalStateException("Wallet operation key conflict")) : Uni.createFrom().voidItem());
                    return eventType(session,system).chain(type -> WalletRows.role(session,system,"WalletEventType","EventXEventType")
                            .chain(typeRole -> WalletRows.role(session,system,name,"EventXClassification")
                                    .chain(actionRole -> WalletRows.role(session,system,"WalletMovement","EventXArrangement").chain(arrangementRole -> {
                                        Event event=new Event().setId(id);
                                        EventXEventType typed=new EventXEventType().setEventID(event).setEventTypeID(new EventType().setId(type));
                                        typed.setId(UUID.randomUUID()); typed.setClassificationID(typeRole); typed.setValue("1");
                                        EventXClassification classified=new EventXClassification().setEventID(event);
                                        classified.setId(UUID.randomUUID()); classified.setClassificationID(actionRole); classified.setValue("1");
                                        Uni<Void> writes=WalletRows.persist(session,event,system,identity)
                                                .chain(() -> WalletRows.persist(session,typed,system,identity))
                                                .chain(() -> WalletRows.persist(session,classified,system,identity)).replaceWithVoid();
                                        for (UUID arrangement : List.of(request.sourceId(),request.destinationId())) {
                                            EventXArrangement link=new EventXArrangement().setEventID(event).setArrangementID(new Arrangement().setId(arrangement));
                                            link.setId(UUID.randomUUID()); link.setClassificationID(arrangementRole); link.setValue("1");
                                            writes=writes.chain(() -> WalletRows.persist(session,link,system,identity).replaceWithVoid());
                                        }
                                        return writes;
                                    }))));
                });
    }
    private Uni<Void> checkKind(Mutiny.StatelessSession session, ISystems<?, ?> system, UUID id, String type) {
        return session.createNativeQuery("select 1 from arrangement.arrangementxarrangementtype x "
                        +"join arrangement.arrangementtype t on t.arrangementtypeid=x.arrangementtypeid "
                        +"where x.arrangementid=:id and x.enterpriseid=:enterprise and t.enterpriseid=:enterprise "
                        +"and t.arrangementtypename=:type and x.effectivefromdate<=statement_timestamp() and x.effectivetodate>statement_timestamp() for share of x,t",Integer.class)
                .setParameter("id",id).setParameter("enterprise",system.getEnterprise().getId()).setParameter("type",type)
                .getResultList().chain(rows -> WalletAuthority.require(!rows.isEmpty()));
    }
    private Uni<UUID> arrangementType(Mutiny.StatelessSession session, ISystems<?, ?> system, String type) {
        return id(session,system,"select arrangementtypeid from arrangement.arrangementtype where enterpriseid=:enterprise and arrangementtypename=:name",type);
    }
    private Uni<UUID> eventType(Mutiny.StatelessSession session, ISystems<?, ?> system) {
        return id(session,system,"select eventtypeid from event.eventtype where enterpriseid=:enterprise and eventtypename=:name",WalletSystem.TRANSACTION_EVENT_TYPE);
    }
    private Uni<UUID> transactionType(Mutiny.StatelessSession session, ISystems<?, ?> system, String type) {
        return id(session,system,"select transaction_type_id from transactions.transaction_type where enterprise_id=:enterprise and code=:name and active=true",type);
    }
    private Uni<UUID> id(Mutiny.StatelessSession session, ISystems<?, ?> system, String sql, String name) {
        return session.createNativeQuery(sql,UUID.class).setParameter("enterprise",system.getEnterprise().getId())
                .setParameter("name",name).getResultList().chain(rows -> rows.size()==1 ? Uni.createFrom().item(rows.getFirst())
                        : Uni.createFrom().failure(new IllegalStateException("Wallet taxonomy unavailable: "+name)));
    }
    private static UUID key(String kind, UUID enterprise, UUID operation) {
        return UUID.nameUUIDFromBytes(("wallet:"+kind+":"+enterprise+":"+operation).getBytes(StandardCharsets.UTF_8));
    }
}
