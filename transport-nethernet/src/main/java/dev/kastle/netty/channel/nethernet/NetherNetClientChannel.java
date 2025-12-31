package dev.kastle.netty.channel.nethernet;

import dev.kastle.netty.channel.nethernet.config.NetherNetAddress;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetSignaling;
import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.RTCBundlePolicy;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCDataChannelBuffer;
import dev.kastle.webrtc.RTCDataChannelInit;
import dev.kastle.webrtc.RTCDataChannelObserver;
import dev.kastle.webrtc.RTCDataChannelState;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCIceServer;
import dev.kastle.webrtc.RTCOfferOptions;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetClientChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetClientChannel.class);

    private final PeerConnectionFactory factory;    
    private final NetherNetSignaling signaling;

    private volatile long connectionId; // Session ID (Long)
    private volatile String targetNetworkId; // Peer ID (String, for Realms)
    
    private volatile boolean handshakeComplete = false;

    private ChannelPromise connectPromise;

    private static final int HANDSHAKE_TIMEOUT_MS = 3000;
    private volatile ScheduledFuture<?> handshakeTimeoutTask;

    private volatile String localUfrag;
    
    public NetherNetClientChannel(NetherNetSignaling signaling) {
        this(new PeerConnectionFactory(), signaling);
    }

    public NetherNetClientChannel(PeerConnectionFactory factory, NetherNetSignaling signaling) {
        super(null, null, null);
        this.factory = factory;
        this.signaling = signaling;
        this.connectionId = ThreadLocalRandom.current().nextLong();
    }

    public void setTargetNetworkId(String id) {
        this.targetNetworkId = id;
    }

    @Override
    public boolean isActive() {
        return super.isActive() && handshakeComplete;
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        if (handshakeTimeoutTask != null) {
            handshakeTimeoutTask.cancel(false);
        }
        if (signaling != null) signaling.close();
        if (connectPromise != null && !connectPromise.isDone()) {
            connectPromise.tryFailure(new ClosedChannelException());
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new NetherNetClientUnsafe();
    }

    private class NetherNetClientUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) return;
            NetherNetClientChannel.this.connectPromise = promise;

            if (remote instanceof NetherNetAddress) {
                String targetId = ((NetherNetAddress) remote).getNetworkId();
                NetherNetClientChannel.this.setTargetNetworkId(targetId);
                NetherNetClientChannel.this.remoteAddress = remote;
            } else if (remote instanceof InetSocketAddress) {
                NetherNetClientChannel.this.remoteAddress = (InetSocketAddress) remote;
                NetherNetClientChannel.this.setTargetNetworkId("0"); // "0" triggers auto-discovery in signaling
            } else {
                promise.setFailure(new IllegalArgumentException("Unsupported address: " + remote.getClass()));
                return;
            }

            eventLoop().execute(() -> startHandshake());
        }
    }

    private void startHandshake() {
        if (!isOpen() || handshakeComplete) return;

        log.debug("Starting Handshake with Connection ID: {}", connectionId);

        if (handshakeTimeoutTask != null) handshakeTimeoutTask.cancel(false);
        handshakeTimeoutTask = eventLoop().schedule(() -> {
            if (!handshakeComplete) {
                log.info("Handshake timed out. Resetting and Retrying...");
                resetAndRetryHandshake();
            }
        }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        signaling.setSignalHandler(connectionId, this::handleSignal);

        signaling.connect(remoteAddress).thenAcceptAsync(iceServers -> {
            if (handshakeComplete) return; 
            try {
                // If this is a retry, peerConnection might be null, so we recreate it
                if (peerConnection == null) {
                    initWebRTC(iceServers);
                    createAndSendOffer();
                }
            } catch (Exception e) {
                log.error("WebRTC Init failed", e);
                // We don't fail promise here; we let the timeout task trigger a retry
            }
        }, eventLoop()).exceptionally(e -> {
            log.error("Signaling connection failed", e);
            // Again, let timeout handle the retry loop
            return null;
        });
    }

    private void resetAndRetryHandshake() {
        if (!isOpen()) return;

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        // Generate new ID for the new attempt
        this.connectionId = ThreadLocalRandom.current().nextLong();
        
        // Restart flow
        startHandshake();
    }

    private void initWebRTC(List<NetherNetSignaling.IceServerInfo> iceServers) {
        RTCConfiguration rtcConfig = new RTCConfiguration();
        rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

        if (iceServers != null) {
            for (NetherNetSignaling.IceServerInfo info : iceServers) {
                RTCIceServer iceServer = new RTCIceServer();
                iceServer.urls = info.urls;
                iceServer.username = info.username;
                iceServer.password = info.password;
                rtcConfig.iceServers.add(iceServer);
            }
        }

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // Wait until we have the ufrag (usually available immediately after createOffer)
                if (localUfrag == null) {
                    log.warn("Generated ICE candidate before local ufrag was available. Skipping.");
                    return;
                }

                String sdp = candidate.sdp.trim();
                
                // Format: <StandardSDP> ufrag <LocalUfrag> network-id <LocalNetworkID> network-cost 0
                StringBuilder sb = new StringBuilder(sdp);
                sb.append(" ufrag ").append(localUfrag);
                sb.append(" network-id ").append(signaling.getLocalNetworkId());
                sb.append(" network-cost 0");

                String payload = NetherNetConstants.SIGNAL_CANDIDATE_ADD + " " + connectionId + " " + sb.toString();
                signaling.sendSignal(targetNetworkId, payload);
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.FAILED) {
                    // Fast fail trigger: retry immediately instead of waiting for timeout
                    eventLoop().execute(() -> {
                        if (!handshakeComplete) resetAndRetryHandshake();
                    });
                }
            }

            @Override public void onDataChannel(RTCDataChannel dataChannel) { }
        });

        setupDataChannels();
    }

    private String extractUfrag(String sdp) {
        if (sdp == null) return "";
        for (String line : sdp.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("a=ice-ufrag:")) {
                return line.substring("a=ice-ufrag:".length()).trim();
            }
            // Some implementations might omit 'a='
            if (line.startsWith("ice-ufrag:")) {
                return line.substring("ice-ufrag:".length()).trim();
            }
        }
        log.warn("Could not find ice-ufrag in local SDP!");
        return "";
    }

    private void createAndSendOffer() {
        if (peerConnection == null) return;
        peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                if (peerConnection == null) return;
                NetherNetClientChannel.this.localUfrag = extractUfrag(description.sdp);
                peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        String payload = NetherNetConstants.SIGNAL_CONNECT_REQUEST + " " + connectionId + " " + description.sdp;
                        signaling.sendSignal(targetNetworkId, payload);
                    }
                    @Override public void onFailure(String error) { /* Retry handled by timeout */ }
                });
            }
            @Override public void onFailure(String error) { /* Retry handled by timeout */ }
        });
    }

    private void handleSignal(String signal) {
        String[] parts = signal.split(" ", 3);
        if (parts.length < 3) return;
        String type = parts[0];
        String data = parts[2];

        eventLoop().execute(() -> {
            if (peerConnection == null) return;
            switch (type) {
                case NetherNetConstants.SIGNAL_CONNECT_RESPONSE:
                    peerConnection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, data), new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {}
                        @Override public void onFailure(String e) { /* Retry handled by timeout */ }
                    });
                    break;
                case NetherNetConstants.SIGNAL_CANDIDATE_ADD:
                    peerConnection.addIceCandidate(new RTCIceCandidate("0", 0, data));
                    break;
                case NetherNetConstants.SIGNAL_CONNECT_ERROR:
                    // Server rejected us (e.g. offline). Reset immediately.
                    resetAndRetryHandshake();
                    break;
            }
        });
    }

    private void setupDataChannels() {
        RTCDataChannelInit reliableInit = new RTCDataChannelInit();
        reliableInit.ordered = true;
        reliableInit.protocol = NetherNetConstants.RELIABLE_CHANNEL_LABEL;

        RTCDataChannelInit unreliableInit = new RTCDataChannelInit();
        unreliableInit.ordered = false;
        unreliableInit.maxRetransmits = 0;

        RTCDataChannel reliable = peerConnection.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, reliableInit);
        RTCDataChannel unreliable = peerConnection.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, unreliableInit);

        reliable.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onStateChange() {
                if (reliable.getState() == RTCDataChannelState.OPEN) {
                    eventLoop().execute(() -> {
                        if (!handshakeComplete) {
                            log.info("NetherNet Connection Established!");
                            handshakeComplete = true;
                            
                            // Cancel timeout now that we are done
                            if (handshakeTimeoutTask != null) {
                                handshakeTimeoutTask.cancel(false);
                            }
                            
                            setDataChannels(reliable, unreliable);
                            if (connectPromise != null && !connectPromise.isDone()) {
                                connectPromise.trySuccess();
                            }
                            pipeline().fireChannelActive();
                        }
                    });
                }
            }
            @Override public void onBufferedAmountChange(long previousAmount) {}
            @Override public void onMessage(RTCDataChannelBuffer buffer) {
                ReferenceCountUtil.release(buffer);
            }
        });
    }
}