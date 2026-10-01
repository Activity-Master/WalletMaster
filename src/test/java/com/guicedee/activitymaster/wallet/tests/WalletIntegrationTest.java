package com.guicedee.activitymaster.wallet.tests;

import com.guicedee.activitymaster.wallet.*;
import com.guicedee.activitymaster.ScopedFsdmFixture;
import com.guicedee.activitymaster.wallet.graphql.WalletGraphQLSchemaProvider;
import com.guicedee.activitymaster.wallet.rest.WalletRestService;
import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.administration.ActivityMasterConfiguration;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Context;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Realm;
import com.guicedee.client.IGuiceContext;
import com.google.inject.Key;
import com.google.inject.name.Names;
import graphql.*;
import graphql.schema.idl.*;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.guicedee.activitymaster.wallet.WalletModels.*;

/** Executes the production stateless wallet service and transport adapters against PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletIntegrationTest {
    private static final String ENTERPRISE="TestEnterprise";
    private Mutiny.SessionFactory factory;
    private WalletApi api;
    private WalletIdentity identity;
    private UUID systemId;
    private UUID clearing;
    private PostgreSQLContainer<?> postgres;
    private ScopedFsdmFixture scoped;
    @BeforeAll void boot() throws Exception {
        System.setProperty("HTTP_PORT", "0");
        Class<?> database=Class.forName("com.guicedee.activitymaster.PostgreSQLTestDBModule");
        var getContainer=database.getMethod("getPostgresContainer"); getContainer.setAccessible(true);
        postgres=(PostgreSQLContainer<?>)getContainer.invoke(null);
        // The shared legacy fixture omits PK constraints; add only the keys referenced by the managed migrations.
        for (String pair:List.of("dbo.enterprise:enterpriseid","dbo.systems:systemid","dbo.activeflag:activeflagid",
                "party.involvedparty:involvedpartyid","party.involvedpartyorganic:involvedpartyorganicid",
                "event.event:eventid","arrangement.arrangement:arrangementid","classification.classification:classificationid",
                "security.securitytoken:securitytokenid","resource.resourceitem:resourceitemid","product.product:productid",
                "address.address:addressid","geography.geography:geographyid","rules.rules:rulesid")) {
            String[] parts=pair.split(":");
            sql("CREATE UNIQUE INDEX wallet_test_"+parts[0].replace('.','_')+" ON "+parts[0]+"("+parts[1]+")");
        }
        for (String name:List.of("transactions.sql")) {
            try (var module=ModuleLayer.boot().configuration().findModule("com.guicedee.activitymaster.fsdm").orElseThrow().reference().open();
                 var script=module.open("db/"+name).orElseThrow()) {
                postgres.copyFileToContainer(Transferable.of(script.readAllBytes()),"/tmp/"+name);
            }
            var result=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"-f","/tmp/"+name);
            assertEquals(0,result.getExitCode(),result.getStderr());
        }
        ActivityMasterConfiguration.get().setApplicationEnterpriseName(ENTERPRISE);
        IGuiceContext.instance();
        factory=IGuiceContext.get(Key.get(Mutiny.SessionFactory.class,Names.named("ActivityMaster-Test")));
        IEnterpriseService<?> enterprises=IGuiceContext.get(IEnterpriseService.class);
        await(factory.withStatelessTransaction(session -> enterprises.getEnterprise(session,ENTERPRISE)
                .onFailure().recoverWithUni(failure -> {
                    var enterprise=enterprises.get(); enterprise.setName(ENTERPRISE); enterprise.setDescription("Wallet integration fixture");
                    return enterprises.createNewEnterprise(session,enterprise);
                }).replaceWithVoid()));
        await(factory.withStatelessSession(session -> enterprises.startNewEnterprise(session,ENTERPRISE,"admin","adminadmin!@")));
        sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        sql("SELECT pg_stat_statements_reset()");
        await(factory.withStatelessTransaction(session -> enterprises.getEnterprise(session,ENTERPRISE)
                .chain(enterprise -> enterprises.loadUpdates(session,enterprise))));
        identity=await(SessionUtils.withActivityMaster(ENTERPRISE,WalletSystem.NAME,tuple -> {
            systemId=tuple.getItem3().getId();
            return com.guicedee.activitymaster.BuiltInPluginFixture.administrator(tuple.getItem1(), tuple.getItem2())
                    .map(user -> new WalletIdentity(user.partyId(), user.enterpriseId(),
                            new Context(Realm.WORK, user.enterpriseId()), user.identityToken()));
        }));
        provisionBuiltin(WalletSystem.NAME);
        provisionGrants();
        IWalletService<?> service=IGuiceContext.get(IWalletService.class);
        api=new WalletApi(() -> Uni.createFrom().item(identity),service);
        // Host provisioning of a clearing account uses the same canonical Arrangement. Wallet API never creates one implicitly.
        clearing=await(api.create(ENTERPRISE,new Create(UUID.randomUUID()))).arrangementId();
        sql("UPDATE arrangement.arrangementxarrangementtype SET arrangementtypeid=(SELECT arrangementtypeid FROM arrangement.arrangementtype WHERE arrangementtypename='Wallet Clearing' AND enterpriseid='"+identity.enterpriseId()+"') WHERE arrangementid='"+clearing+"'");
        writeQueryStats("wallet-setup-queries.csv");
        sql("SELECT pg_stat_statements_reset()");
    }
    private void provisionBuiltin(String name) {
        var plugins = IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class);
        var user = new com.guicedee.activitymaster.fsdm.plugins.PluginModels.Identity(identity.partyId(), identity.enterpriseId(), identity.identityToken());
        await(SessionUtils.withActivityMaster(ENTERPRISE, name, t ->
                plugins.find(t.getItem1(), t.getItem3(), user, t.getItem3().getId())
                        .chain(plugin -> plugins.install(t.getItem1(), t.getItem3(), user, plugin.id(), identity.installationPartyId())
                                .chain(() -> {
                                    Uni<Void> chain = Uni.createFrom().voidItem();
                                    for (UUID dependency : plugin.systems())
                                        chain = chain.chain(() -> plugins.consent(t.getItem1(), t.getItem3(), user,
                                                new com.guicedee.activitymaster.fsdm.plugins.PluginModels.Invocation(plugin.id(), identity.installationPartyId()), dependency, true));
                                    return chain;
                                }))));
    }
    @AfterAll void writeQueryPerformance() throws Exception {
        writeQueryStats("wallet-postgres-queries.csv");
    }
    private void writeQueryStats(String filename) throws Exception {
        String query = "COPY (SELECT calls, round(total_exec_time::numeric,3) AS total_ms, "
            + "round(mean_exec_time::numeric,3) AS mean_ms, round(max_exec_time::numeric,3) AS max_ms, "
            + "rows, shared_blks_hit, shared_blks_read, "
            + "regexp_replace(query, E'[\\n\\r\\t]+', ' ', 'g') AS sql "
            + "FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) "
            + "AND query ~* '(transactions[.]|event[.]|arrangement[.]|classification[.]|security[.]|party[.])' "
            + "ORDER BY total_exec_time DESC) TO STDOUT WITH CSV HEADER";
        var result=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),
            "-d",postgres.getDatabaseName(),"-c",query);
        assertEquals(0,result.getExitCode(),result.getStderr());
        assertTrue(result.getStdout().contains("shared_blks_read,sql"),"Query statistics were not collected");
        Path directory=Path.of("target","performance");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(filename),result.getStdout());
    }
    private void provisionGrants() throws Exception {
        scoped=new ScopedFsdmFixture(this::sql,identity.enterpriseId(),identity.identityToken());
        scoped.install(systemId,"WORK",identity.enterpriseId(),"wallet");
        for(String action:List.of("create","read","post","transfer","deposit","withdrawal")) {
            scoped.grant(systemId,"WORK",identity.enterpriseId(),"wallet",identity.partyId(),"wallet."+action);
        }
    }
    @Test void administratorCanDisableTheWalletPluginDependency() {
        var plugins = IGuiceContext.get(com.guicedee.activitymaster.fsdm.plugins.PluginService.class);
        var user = new com.guicedee.activitymaster.fsdm.plugins.PluginModels.Identity(
                identity.partyId(), identity.enterpriseId(), identity.identityToken());
        UUID dependency = await(SessionUtils.withActivityMaster(ENTERPRISE, WalletSystem.NAME, t ->
                plugins.find(t.getItem1(), t.getItem3(), user, systemId).map(plugin -> plugin.systems().iterator().next())));
        await(SessionUtils.withActivityMaster(ENTERPRISE, WalletSystem.NAME, t ->
                plugins.setSystemAccess(t.getItem1(), t.getItem3(), user, systemId, dependency, null, false)));
        try {
            assertThrows(SecurityException.class, () -> api.balance(ENTERPRISE, clearing, "POINTS").await().atMost(Duration.ofSeconds(30)));
        } finally {
            await(SessionUtils.withActivityMaster(ENTERPRISE, WalletSystem.NAME, t ->
                    plugins.setSystemAccess(t.getItem1(), t.getItem3(), user, systemId, dependency, null, true)));
        }
    }
    @Test void productionDepositTransferWithdrawalAndRetryAreAtomic() {
        UUID a=wallet(),b=wallet();
        Movement deposit=movement(clearing,a,"100.00");
        Receipt receipt=await(api.move(ENTERPRISE,Action.DEPOSIT,deposit));
        assertEquals(receipt,await(api.move(ENTERPRISE,Action.DEPOSIT,deposit)));
        Movement transfer=movement(a,b,"12.50");
        await(api.move(ENTERPRISE,Action.TRANSFER,transfer));
        await(api.move(ENTERPRISE,Action.WITHDRAWAL,movement(b,clearing,"2.50")));
        assertEquals("87.50000000",await(api.balance(ENTERPRISE,a,"POINTS")).amount());
        assertEquals("10.00000000",await(api.balance(ENTERPRISE,b,"POINTS")).amount());
        assertEquals(2,await(api.history(ENTERPRISE,b,"POINTS",0,10)).size());
        assertThrows(RuntimeException.class,() -> await(api.move(ENTERPRISE,Action.TRANSFER,
                new Movement(transfer.operationKey(),a,b,"13","POINTS"))));
        assertEquals("10.00000000",await(api.balance(ENTERPRISE,b,"POINTS")).amount());
    }
    @Test void concurrentEventsCannotSpendTheSameFunds() {
        UUID a=wallet(),b=wallet();
        await(api.move(ENTERPRISE,Action.DEPOSIT,movement(clearing,a,"10")));
        var first=api.move(ENTERPRISE,Action.TRANSFER,movement(a,b,"7")).subscribeAsCompletionStage();
        var second=api.move(ENTERPRISE,Action.TRANSFER,movement(a,b,"7")).subscribeAsCompletionStage();
        int success=0;
        try { first.toCompletableFuture().join(); success++; } catch(RuntimeException ignored) { }
        try { second.toCompletableFuture().join(); success++; } catch(RuntimeException ignored) { }
        assertEquals(1,success);
        assertEquals("3.00000000",await(api.balance(ENTERPRISE,a,"POINTS")).amount());
        long events=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
                "select count(*) from event.eventxarrangement where arrangementid=:id",Long.class).setParameter("id",a).getSingleResult()));
        assertEquals(2L,events,"The losing operation must roll back its newly created Event relationships");
    }
    @Test void concurrentRetriesCommitOnlyOneMovement() {
        UUID a=wallet(); Movement deposit=movement(clearing,a,"5");
        var first=api.move(ENTERPRISE,Action.DEPOSIT,deposit).subscribeAsCompletionStage();
        var second=api.move(ENTERPRISE,Action.DEPOSIT,deposit).subscribeAsCompletionStage();
        assertEquals(first.toCompletableFuture().join(),second.toCompletableFuture().join());
        assertEquals("5.00000000",await(api.balance(ENTERPRISE,a,"POINTS")).amount());
        assertEquals(1,await(api.history(ENTERPRISE,a,"POINTS",0,10)).size());
    }

    @Test void grantsAreRecheckedForRetriesAndReads() throws Exception {
        UUID a=wallet(); Movement deposit=movement(clearing,a,"4");
        await(api.move(ENTERPRISE,Action.DEPOSIT,deposit));
        UUID grant=ScopedFsdmFixture.grantId(systemId,"WORK",identity.enterpriseId(),"wallet",identity.partyId(),"wallet.deposit");
        scoped.disable(grant);
        try { assertThrows(SecurityException.class,() -> await(api.move(ENTERPRISE,Action.DEPOSIT,deposit))); }
        finally { scoped.enable(grant); }
        sql("UPDATE arrangement.arrangementsecuritytoken SET readallowed=0,createallowed=0,updateallowed=0 WHERE arrangementid='"+a+"'");
        assertThrows(SecurityException.class,() -> await(api.balance(ENTERPRISE,a,"POINTS")));
    }
    @Test void restAndGraphqlExecuteTheSameAuthorizedService() throws Exception {
        assertNotNull(IGuiceContext.get(GraphQL.class).getGraphQLSchema().getMutationType().getFieldDefinition("walletCreate"));
        WalletRestService rest=new WalletRestService(api);
        UUID a=await(rest.create(ENTERPRISE,new Create(UUID.randomUUID()))).arrangementId();
        await(rest.deposit(ENTERPRISE,movement(clearing,a,"8")));
        var provider=new WalletGraphQLSchemaProvider(api);
        TypeDefinitionRegistry registry=new SchemaParser().parse("type Query { ping: String } type Mutation { ping: String }");
        registry.merge(provider.getTypeDefinitions());
        GraphQL graph=GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(registry,
                provider.configureWiring(RuntimeWiring.newRuntimeWiring()).build())).build();
        var result=graph.executeAsync(ExecutionInput.newExecutionInput()
                .query("query($enterprise:String!,$id:ID!){walletBalance(enterprise:$enterprise,walletId:$id,unit:\"POINTS\"){amount}}")
                .variables(Map.of("enterprise",ENTERPRISE,"id",a.toString())).build()).get();
        assertTrue(result.getErrors().isEmpty(),result.getErrors().toString());
        assertEquals("8.00000000",((Map<?,?>)((Map<?,?>)result.getData()).get("walletBalance")).get("amount"));
        assertEquals("8.00000000",await(rest.balance(ENTERPRISE,a,"POINTS")).amount());
        var withdrawal=graph.executeAsync(ExecutionInput.newExecutionInput()
                .query("mutation($enterprise:String!,$input:WalletMovementInput!){walletWithdraw(enterprise:$enterprise,input:$input){eventId lines{direction amount}}}")
                .variables(Map.of("enterprise",ENTERPRISE,"input",Map.of("operationKey",UUID.randomUUID().toString(),
                        "sourceId",a.toString(),"destinationId",clearing.toString(),"amount","3","unit","POINTS"))).build()).get();
        assertTrue(withdrawal.getErrors().isEmpty(),withdrawal.getErrors().toString());
        assertEquals("5.00000000",await(rest.balance(ENTERPRISE,a,"POINTS")).amount());
        var created=graph.executeAsync(ExecutionInput.newExecutionInput()
                .query("mutation($enterprise:String!,$input:WalletCreateInput!){walletCreate(enterprise:$enterprise,input:$input){arrangementId involvedPartyId}}")
                .variables(Map.of("enterprise",ENTERPRISE,"input",Map.of("operationKey",UUID.randomUUID().toString()))).build()).get();
        assertTrue(created.getErrors().isEmpty(),created.getErrors().toString());
        assertEquals(identity.partyId().toString(),((Map<?,?>)((Map<?,?>)created.getData()).get("walletCreate")).get("involvedPartyId"));
    }
    @Test void missingAuthenticationAndWrongEnterpriseDenyBeforeWalletAccess() {
        WalletApi denied=new WalletApi(new WalletIdentityProvider.Deny(),IGuiceContext.get(IWalletService.class));
        assertThrows(SecurityException.class,() -> await(denied.create(ENTERPRISE,new Create(UUID.randomUUID()))));
        assertThrows(SecurityException.class,() -> new WalletIdentity(identity.partyId(),UUID.randomUUID(),identity.context(),identity.identityToken()));
        UUID otherEnterprise=UUID.randomUUID();
        WalletIdentity foreign=new WalletIdentity(identity.partyId(),otherEnterprise,new Context(Realm.WORK,otherEnterprise),identity.identityToken());
        WalletApi wrongTenant=new WalletApi(() -> Uni.createFrom().item(foreign),IGuiceContext.get(IWalletService.class));
        assertThrows(SecurityException.class,() -> await(wrongTenant.create(ENTERPRISE,new Create(UUID.randomUUID()))));
        assertThrows(jakarta.ws.rs.ForbiddenException.class,() -> await(new WalletRestService(denied).create(ENTERPRISE,new Create(UUID.randomUUID()))));
    }
    private UUID wallet() { return await(api.create(ENTERPRISE,new Create(UUID.randomUUID()))).arrangementId(); }
    private Movement movement(UUID source, UUID destination, String amount) { return new Movement(UUID.randomUUID(),source,destination,amount,"POINTS"); }
    private static <T> T await(Uni<T> uni) { return uni.await().atMost(Duration.ofSeconds(90)); }
    private void sql(String sql) throws Exception {
        var result=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"-c",sql);
        assertEquals(0,result.getExitCode(),result.getStderr());
    }
}
