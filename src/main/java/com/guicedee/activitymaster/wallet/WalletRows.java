package com.guicedee.activitymaster.wallet;

import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.db.abstraction.WarehouseSCDTable;
import com.guicedee.activitymaster.fsdm.db.entities.classifications.Classification;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;

/** Internal warehouse persistence helpers; never opens another session or transaction. */
final class WalletRows {
    static <R extends WarehouseSCDTable<R, ?, ?, ?>> Uni<R> persist(
            Mutiny.StatelessSession session, R row, ISystems<?, ?> system, WalletIdentity identity) {
        row.setEnterpriseID(system.getEnterprise()).setSystemID(system).setOriginalSourceSystemID(system);
        IActiveFlagService<?> flags=IGuiceContext.get(IActiveFlagService.class);
        ISecurityTokenService<?> security=IGuiceContext.get(ISecurityTokenService.class);
        return flags.getActiveFlag(session, system.getEnterprise(), identity.tokens()).chain(flag -> {
            row.setActiveFlagID(flag);
            return row.builder(session).persist(row)
                    .chain(() -> security.resolveDefaultGroupFolderTokens(session, system, identity.tokens()))
                    .chain(groups -> row.createScopeRestrictedSecurity(session, system, system.getEnterprise(),flag,groups,null,identity.tokens()))
                    .chain(() -> security.getSecurityToken(session,identity.identityToken(),system,identity.tokens())
                            .onItem().ifNull().failWith(() -> new SecurityException("Wallet identity token unavailable")))
                    .chain(token -> row.createSecurityGrant(session,system,system.getEnterprise(),flag,
                            token,true,true,false,true,identity.tokens()))
                    .replaceWith(row);
        });
    }
    static Uni<Classification> role(Mutiny.StatelessSession session, ISystems<?, ?> system, String name, String concept) {
        return session.createQuery("select c.id from Classification c join c.concept d where c.name=:name and d.name=:concept "
                        +"and c.enterpriseID.id=:enterprise and c.systemID.id=:system "
                        +"and c.effectiveFromDate<=current_timestamp and c.effectiveToDate>current_timestamp",UUID.class)
                .setParameter("name",name).setParameter("concept",concept)
                .setParameter("enterprise",system.getEnterprise().getId()).setParameter("system",system.getId())
                .getResultList().chain(rows -> rows.size()==1 ? Uni.createFrom().item(new Classification().setId(rows.getFirst()))
                        : Uni.createFrom().failure(new IllegalStateException("Wallet taxonomy unavailable: "+name)));
    }
    static Uni<Void> lock(Mutiny.StatelessSession session, UUID id) {
        return session.createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(:key,0))",Integer.class)
                .setParameter("key",id.toString()).getSingleResult().replaceWithVoid();
    }
}
