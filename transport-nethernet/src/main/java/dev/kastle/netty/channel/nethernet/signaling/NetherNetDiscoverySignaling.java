package dev.kastle.netty.channel.nethernet.signaling;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class NetherNetDiscoverySignaling implements NetherNetSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscoverySignaling.class);

    private final NetherNetDiscovery discovery;
    private final InetSocketAddress bindAddress;
    private final String localNetworkId;
    
    // State captured after connect
    private volatile InetSocketAddress remoteAddress;
    private final AtomicReference<String> discoveredServerId = new AtomicReference<>(null);

    public NetherNetDiscoverySignaling() {
        this(ThreadLocalRandom.current().nextLong(), new InetSocketAddress(0));
    }

    public NetherNetDiscoverySignaling(long localNetworkId) {
        this(localNetworkId, new InetSocketAddress(0));
    }

    public NetherNetDiscoverySignaling(long localNetworkId, InetSocketAddress bindAddress) {
        this.localNetworkId = Long.toUnsignedString(localNetworkId);
        this.discovery = new NetherNetDiscovery(localNetworkId);
        this.bindAddress = bindAddress;
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remote) {
        CompletableFuture<List<IceServerInfo>> future = new CompletableFuture<>();
        
        if (!(remote instanceof InetSocketAddress)) {
            future.completeExceptionally(new IllegalArgumentException("Discovery requires InetSocketAddress"));
            return future;
        }
        
        this.remoteAddress = (InetSocketAddress) remote;

        try {
            if (!discovery.isActive()) {
                log.info("Binding NetherNet Discovery to {}", bindAddress);
                discovery.bind(bindAddress);
            }

            log.debug("Sending Discovery Request to {}", remote);
            
            // Send request and register the callback to capture the ID
            discovery.sendDiscoveryRequest(this.remoteAddress, (serverNetworkId, payload) -> {
                try {
                    log.info("Discovery Response Received! Server NetworkID: {}", serverNetworkId);
                    
                    // Capture the ID so we can use it for signaling later
                    discoveredServerId.set(Long.toUnsignedString(serverNetworkId));
                    
                    future.complete(Collections.emptyList());
                } catch (Exception e) {
                    log.error("Error processing discovery response", e);
                    future.completeExceptionally(e);
                } finally {
                    ReferenceCountUtil.release(payload);
                }
            });
        } catch (Exception e) {
            log.error("Failed to send discovery request", e);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        if (remoteAddress == null) {
            log.warn("Cannot send signal: Remote address not set (connect() not called?)");
            return;
        }
        
        // If the Channel passed '0' (unknown), use the one we discovered.
        String actualIdStr = targetNetworkId;
        if (actualIdStr == null || actualIdStr.equals("0")) {
            actualIdStr = discoveredServerId.get();
        }

        if (actualIdStr == null) {
            log.warn("Cannot send signal: Unknown Server Network ID.");
            return;
        }
        
        log.trace("Sending Signal to {} (ID: {}): {}", remoteAddress, actualIdStr, data);
        try {
            // LAN protocol strictly requires a Long ID. 
            // If we are trying to connect to a Realm (String ID) via LAN signaling, this is invalid configuration.
            long id = Long.parseUnsignedLong(actualIdStr);
            discovery.sendSignal(remoteAddress, id, data);
        } catch (NumberFormatException e) {
            log.error("Cannot send LAN signal to non-numeric Network ID: {}", actualIdStr);
        }
    }

    @Override
    public void setSignalHandler(long connectionId, Consumer<String> handler) {
        discovery.registerSignalHandler(connectionId, handler);
    }

    @Override
    public void close() {
        discovery.close();
    }
}