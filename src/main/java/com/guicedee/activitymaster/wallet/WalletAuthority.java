package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.db.entities.arrangement.Arrangement;
import com.guicedee.activitymaster.fsdm.db.entities.events.Event;
import com.guicedee.activitymaster.fsdm.db.entities.involvedparty.InvolvedParty;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.*;
import com.guicedee.activitymaster.fsdm.transactions.TransactionService;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;

/** Checks current FSDM rows and token grants, using the host's verified actor token only. */
final class WalletAuthority implements TransactionService.Authority {
    private final WalletIdentity identity;
    private final ISystems<?, ?> system;
    WalletAuthority(WalletIdentity identity, ISystems<?, ?> system) { this.identity=identity; this.system=system; }
    public Uni<Void> currentActor(Mutiny.StatelessSession session, Actor actor, Context context) {
        if (!identity.partyId().equals(actor.partyId()) || !identity.context().equals(context)) return denied();
        return active(session,"party.involvedparty","involvedpartyid",actor.partyId())
                .chain(() -> new InvolvedParty().setId(actor.partyId()).canRead(session,system,identity.tokens()))
                .chain(WalletAuthority::require);
    }
    public Uni<Void> event(Mutiny.StatelessSession session, Actor actor, Context context, UUID id) {
        return active(session,"event.event","eventid",id)
                .chain(() -> new Event().setId(id).canWrite(session,system,identity.tokens()))
                .chain(WalletAuthority::require);
    }
    public Uni<Void> arrangement(Mutiny.StatelessSession session, Actor actor, Context context, UUID id, int direction) {
        Arrangement row=new Arrangement().setId(id);
        return active(session,"arrangement.arrangement","arrangementid",id)
                .chain(() -> direction==0 ? row.canRead(session,system,identity.tokens()) : row.canWrite(session,system,identity.tokens()))
                .chain(WalletAuthority::require);
    }
    private Uni<Void> active(Mutiny.StatelessSession session, String table, String key, UUID id) {
        return session.createNativeQuery("select 1 from "+table+" r join dbo.activeflag f on f.activeflagid=r.activeflagid "
                        +"where r."+key+"=:id and r.enterpriseid=:enterprise and f.allowaccess=1 "
                        +"and r.effectivefromdate<=statement_timestamp() and r.effectivetodate>statement_timestamp() for share of r,f",Integer.class)
                .setParameter("id",id).setParameter("enterprise",identity.enterpriseId()).getResultList()
                .chain(rows -> require(!rows.isEmpty()));
    }
    static Uni<Void> require(boolean allowed) { return allowed ? Uni.createFrom().voidItem() : denied(); }
    static <T> Uni<T> denied() { return Uni.createFrom().failure(new SecurityException("Wallet unavailable in this scope")); }
}
