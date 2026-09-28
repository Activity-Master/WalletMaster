package com.guicedee.activitymaster.wallet;

import com.guicedee.client.services.config.IGuiceScanModuleInclusions;
import java.util.Set;

public final class WalletInclusionModule implements IGuiceScanModuleInclusions<WalletInclusionModule> {
    @Override public Set<String> includeModules() { return Set.of("com.guicedee.activitymaster.wallet"); }
}
