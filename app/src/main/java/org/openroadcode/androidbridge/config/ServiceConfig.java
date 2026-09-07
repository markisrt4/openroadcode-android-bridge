package org.openroadcode.androidbridge.config;

import java.util.Objects;

public final class ServiceConfig {
    private final boolean enabled;
    private final ServiceProvider provider;

    public ServiceConfig(boolean enabled, ServiceProvider provider) {
        this.enabled = enabled;
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public boolean enabled() {
        return enabled;
    }

    public ServiceProvider provider() {
        return provider;
    }

    public ServiceConfig withEnabled(boolean value) {
        return new ServiceConfig(value, provider);
    }

    public ServiceConfig withProvider(ServiceProvider value) {
        return new ServiceConfig(enabled, value);
    }
}
