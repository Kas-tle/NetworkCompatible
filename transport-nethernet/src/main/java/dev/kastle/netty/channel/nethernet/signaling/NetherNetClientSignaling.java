package dev.kastle.netty.channel.nethernet.signaling;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface NetherNetClientSignaling extends NetherNetSignaling {
    /**
     * Connects to the signaling medium (Client mode).
     * 
     * @param remoteAddress The address of the signaling server to connect to.
     */
    CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress);

    /**
     * Sets a handler to be called when a signaling message is received for an unknown connection ID.
     *
     * @param handler The handler to process incoming signaling messages for unknown connection IDs.
     */
    void setNotFoundHandler(Consumer<String> handler);
}
