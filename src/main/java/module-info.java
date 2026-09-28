module com.guicedee.activitymaster.wallet {
    requires transitive com.guicedee.activitymaster.fsdm;
    requires transitive io.vertx.core;
    requires transitive io.vertx.sql.client;
    requires transitive io.smallrye.mutiny;
    exports com.guicedee.activitymaster.wallet;
    exports com.guicedee.activitymaster.wallet.rest;
    exports com.guicedee.activitymaster.wallet.graphql;
    opens com.guicedee.activitymaster.wallet to com.google.guice, tools.jackson.databind;
    opens com.guicedee.activitymaster.wallet.rest to com.google.guice, com.guicedee.rest, tools.jackson.databind;
    opens com.guicedee.activitymaster.wallet.graphql to com.google.guice;
    provides com.guicedee.client.services.lifecycle.IGuiceModule
        with com.guicedee.activitymaster.wallet.WalletModule;
    provides com.guicedee.client.services.config.IGuiceScanModuleInclusions
        with com.guicedee.activitymaster.wallet.WalletInclusionModule;
    provides com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider
        with com.guicedee.activitymaster.wallet.graphql.WalletGraphQLSchemaProvider;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem
        with com.guicedee.activitymaster.wallet.WalletSystem;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate
        with com.guicedee.activitymaster.wallet.WalletSystemInstall;
}
