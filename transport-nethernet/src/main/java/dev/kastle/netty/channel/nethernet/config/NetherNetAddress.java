package dev.kastle.netty.channel.nethernet.config;

import java.net.SocketAddress;

public class NetherNetAddress extends SocketAddress {
    private final String networkId;

    public NetherNetAddress(long networkId) {
        this.networkId = Long.toUnsignedString(networkId);
    }

    public NetherNetAddress(String networkId) {
        this.networkId = networkId;
    }

    public String getNetworkId() {
        return networkId;
    }

    /**
     * Tries to parse the Network ID as a long.
     * @return the long value
     * @throws NumberFormatException if the ID is not a valid unsigned long string (e.g. Realms ID).
     */
    public long getNetworkIdAsLong() {
        return Long.parseUnsignedLong(networkId);
    }
    
    @Override
    public String toString() {
        return "NetherNetAddress(" + networkId + ")";
    }
}
