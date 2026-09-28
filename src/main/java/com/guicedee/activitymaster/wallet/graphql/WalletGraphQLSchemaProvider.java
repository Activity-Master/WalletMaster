package com.guicedee.activitymaster.wallet.graphql;

import com.guicedee.activitymaster.wallet.*;
import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.*;
import java.util.Map;
import java.util.UUID;
import static com.guicedee.activitymaster.wallet.WalletModels.*;

/** Auto-discovered wallet queries and mutations; shares the REST service's identity boundary. */
public final class WalletGraphQLSchemaProvider implements IGraphQLSchemaProvider<WalletGraphQLSchemaProvider> {
    private final WalletApi suppliedApi;
    public WalletGraphQLSchemaProvider() { suppliedApi=null; }
    public WalletGraphQLSchemaProvider(WalletApi api) { suppliedApi=api; }
    private WalletApi api() { return suppliedApi==null ? IGuiceContext.get(WalletApi.class) : suppliedApi; }
    private static final String SDL="""
        type WalletAccount { arrangementId: ID!, involvedPartyId: ID! }
        type WalletBalance { arrangementId: ID!, unit: String!, amount: String! }
        type WalletLine { number: Int!, arrangementId: ID!, transactionTypeId: ID!, direction: Int!, amount: String!, unit: String! }
        type WalletReceipt { eventId: ID!, operationKey: ID!, lines: [WalletLine!]! }
        type WalletHistoryLine { transactionId: ID!, eventId: ID!, lineNumber: Int!, direction: Int!, amount: String!, unit: String! }
        input WalletCreateInput { operationKey: ID! }
        input WalletMovementInput { operationKey: ID!, sourceId: ID!, destinationId: ID!, amount: String!, unit: String! }
        extend type Query {
            walletBalance(enterprise: String!, walletId: ID!, unit: String!): WalletBalance!
            walletHistory(enterprise: String!, walletId: ID!, unit: String!, offset: Int! = 0, limit: Int! = 50): [WalletHistoryLine!]!
        }
        extend type Mutation {
            walletCreate(enterprise: String!, input: WalletCreateInput!): WalletAccount!
            walletTransfer(enterprise: String!, input: WalletMovementInput!): WalletReceipt!
            walletDeposit(enterprise: String!, input: WalletMovementInput!): WalletReceipt!
            walletWithdraw(enterprise: String!, input: WalletMovementInput!): WalletReceipt!
        }
        """;
    public TypeDefinitionRegistry getTypeDefinitions() { return new SchemaParser().parse(SDL); }
    public RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder) {
        return builder.type("Query", q -> q
                .dataFetcher("walletBalance", e -> api().balance(e.getArgument("enterprise"),UUID.fromString(e.getArgument("walletId")),e.getArgument("unit")).subscribeAsCompletionStage())
                .dataFetcher("walletHistory", e -> api().history(e.getArgument("enterprise"),UUID.fromString(e.getArgument("walletId")),e.getArgument("unit"),e.getArgument("offset"),e.getArgument("limit")).subscribeAsCompletionStage()))
                .type("Mutation", m -> m
                        .dataFetcher("walletCreate", e -> {
                            Map<String,Object> input=e.getArgument("input");
                            return api().create(e.getArgument("enterprise"),new Create(UUID.fromString((String)input.get("operationKey")))).subscribeAsCompletionStage();
                        })
                        .dataFetcher("walletTransfer", e -> movement(e,Action.TRANSFER))
                        .dataFetcher("walletDeposit", e -> movement(e,Action.DEPOSIT))
                        .dataFetcher("walletWithdraw", e -> movement(e,Action.WITHDRAWAL)));
    }
    private Object movement(DataFetchingEnvironment e, Action action) {
        Map<String,Object> input=e.getArgument("input");
        Movement request=new Movement(UUID.fromString((String)input.get("operationKey")),UUID.fromString((String)input.get("sourceId")),
                UUID.fromString((String)input.get("destinationId")),(String)input.get("amount"),(String)input.get("unit"));
        return api().move(e.getArgument("enterprise"),action,request).subscribeAsCompletionStage();
    }
}
