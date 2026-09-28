package com.guicedee.activitymaster.wallet;

import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.guicedee.client.services.lifecycle.IGuiceModule;

/** Same raw/generic/concrete service binding contract as ActivityMaster's EventsBinder. */
public final class WalletModule extends AbstractModule implements IGuiceModule<WalletModule> {
    @Override protected void configure() {
        Key<IWalletService<?>> generic = Key.get(new TypeLiteral<IWalletService<?>>() {});
        Key<IWalletService<WalletService>> concrete = Key.get(new TypeLiteral<IWalletService<WalletService>>() {});
        bind(generic).to(concrete);
        bind(concrete).to(WalletService.class);
        bind(IWalletService.class).to(generic);
    }
}
