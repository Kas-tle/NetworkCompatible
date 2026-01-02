package dev.kastle.netty.channel.nethernet.signaling;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface NetherNetClientSignaling extends NetherNetSignaling {
    /**
     * Connects to the signaling medium (Client mode).
     */
    CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress);
}
