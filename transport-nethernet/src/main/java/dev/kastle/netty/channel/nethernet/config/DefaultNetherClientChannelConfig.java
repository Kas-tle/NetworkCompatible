package dev.kastle.netty.channel.nethernet.config;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;

import java.util.Map;

public class DefaultNetherClientChannelConfig extends DefaultNetherChannelConfig  {
    private volatile int clientHandshakeTimeoutMs = 1000;

    public DefaultNetherClientChannelConfig(Channel channel) {
        super(channel);
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return this.getOptions(
                super.getOptions(), NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(ChannelOption<T> option) {
        if (option == NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS) {
            return (T) Integer.valueOf(this.clientHandshakeTimeoutMs);
        }

        return this.channel.parent().config().getOption(option);
    }

    @Override
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        this.validate(option, value);

        if (option == NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS) {
            this.setClientHandshakeTimeoutMs((Integer) value);
            return true;
        } else {
            return this.channel.parent().config().setOption(option, value);
        }
    }

    void setClientHandshakeTimeoutMs(int clientHandshakeTimeoutMs) {
        this.clientHandshakeTimeoutMs = clientHandshakeTimeoutMs;
    }
}
