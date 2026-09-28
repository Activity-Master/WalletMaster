package com.guicedee.activitymaster.wallet.rest;

import com.google.inject.Inject;
import com.guicedee.activitymaster.wallet.*;
import io.smallrye.mutiny.Uni;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static com.guicedee.activitymaster.wallet.WalletModels.*;

@Path("{enterprise}/wallet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name="Wallet Master", description="FSDM wallets; authentication is supplied by the host")
public final class WalletRestService {
    private final WalletApi api;
    @Inject public WalletRestService(WalletApi api) { this.api=api; }
    @POST @Path("accounts") @Operation(summary="Create a wallet for the authenticated involved party")
    public Uni<Wallet> create(@PathParam("enterprise") String enterprise, Create body) {
        return invoke(() -> api.create(enterprise,body));
    }
    @GET @Path("accounts/{id}/balance") @Operation(summary="Read the committed balance for one unit")
    public Uni<Balance> balance(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id, @QueryParam("unit") String unit) {
        return invoke(() -> api.balance(enterprise,id,unit));
    }
    @GET @Path("accounts/{id}/transactions") @Operation(summary="Read bounded transaction history")
    public Uni<List<HistoryLine>> history(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id,
            @QueryParam("unit") String unit, @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return invoke(() -> api.history(enterprise,id,unit,offset,limit));
    }
    @POST @Path("transfers") @Operation(summary="Transfer between authorized wallets")
    public Uni<Receipt> transfer(@PathParam("enterprise") String enterprise, Movement body) {
        return invoke(() -> api.move(enterprise,Action.TRANSFER,body));
    }
    @POST @Path("deposits") @Operation(summary="Deposit from an authorized clearing arrangement")
    public Uni<Receipt> deposit(@PathParam("enterprise") String enterprise, Movement body) {
        return invoke(() -> api.move(enterprise,Action.DEPOSIT,body));
    }
    @POST @Path("withdrawals") @Operation(summary="Withdraw to an authorized clearing arrangement")
    public Uni<Receipt> withdraw(@PathParam("enterprise") String enterprise, Movement body) {
        return invoke(() -> api.move(enterprise,Action.WITHDRAWAL,body));
    }
    private static <T> Uni<T> invoke(Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(work::get)
                .onFailure(SecurityException.class).transform(t -> new ForbiddenException("Wallet access denied"))
                .onFailure(t -> t instanceof IllegalArgumentException || t instanceof NullPointerException)
                    .transform(t -> new BadRequestException("Invalid wallet request"))
                .onFailure(IllegalStateException.class).transform(t -> new ClientErrorException("Wallet operation conflict",409));
    }
}
