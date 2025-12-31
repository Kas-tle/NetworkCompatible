package dev.kastle.netty.channel.nethernet.signaling;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface NetherNetSignaling extends AutoCloseable {

    /**
     * Connects to the signaling medium.
     */
    CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress);

    /**
     * Sends a signaling message to the remote peer.
     *
     * @param targetNetworkId The Network ID of the destination (String to support Realms).
     * @param data            The raw signaling payload.
     */
    void sendSignal(String targetNetworkId, String data);

    void setSignalHandler(long connectionId, Consumer<String> handler);

    /**
     * Returns the Local Network ID of this client as a String.
     * This is required for formatting the 'candidate:' string in SDP.
     */
    String getLocalNetworkId();

    @Override
    void close();

    class IceServerInfo {
        public String username;
        public String password;
        public List<String> urls;
    }
}
