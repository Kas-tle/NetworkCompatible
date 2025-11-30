package dev.kastle.netty.channel.nethernet;

import dev.kastle.netty.handler.codec.nethernet.NetherNetDiscovery;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.PortAllocatorConfig;
import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetClientChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetClientChannel.class);

    private HeadlessAudioDeviceModule audioDeviceModule;
    private PeerConnectionFactory factory;
    
    private final NetherNetDiscovery discovery;
    private final long networkId;
    private volatile long connectionId;
    
    private volatile boolean handshakeComplete = false;

    private static final int HANDSHAKE_TIMEOUT_MS = 500; 
    private volatile ScheduledFuture<?> handshakeTimeoutTask;

    public NetherNetClientChannel() {
        super(null, null, null);
        this.networkId = ThreadLocalRandom.current().nextLong();
        this.connectionId = ThreadLocalRandom.current().nextLong();
        this.discovery = new NetherNetDiscovery(this.networkId);
    }

    public NetherNetClientChannel(long networkId) {
        super(null, null, null);
        this.networkId = networkId;
        this.connectionId = ThreadLocalRandom.current().nextLong();
        this.discovery = new NetherNetDiscovery(this.networkId);
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
        if (discovery != null) discovery.close();
        if (factory != null) factory.dispose();
        if (audioDeviceModule != null) audioDeviceModule.dispose();
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new NetherNetClientUnsafe();
    }

    private void resetAndRetryHandshake() {
        if (!isOpen()) return;

        log.debug("Handshake timed out/failed. Resetting ID and retrying...");

        if (handshakeTimeoutTask != null) {
            handshakeTimeoutTask.cancel(false);
            handshakeTimeoutTask = null;
        }

        if (peerConnection != null) {
            peerConnection.close(); 
            peerConnection = null;
        }
        
        if (discovery != null) {
            discovery.unregisterSignalHandler(this.connectionId);
        }

        this.connectionId = ThreadLocalRandom.current().nextLong();
        
        log.debug("Retrying connection with new Connection ID: {}", this.connectionId);
        eventLoop().execute(() -> startHandshake(this.remoteAddress));
    }

    private class NetherNetClientUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            if (!(remote instanceof InetSocketAddress)) {
                promise.setFailure(new IllegalArgumentException("Unsupported address type"));
                return;
            }

            if (local instanceof InetSocketAddress) {
                NetherNetClientChannel.this.localAddress = (InetSocketAddress) local;
            }
            
            InetSocketAddress remoteAddress = (InetSocketAddress) remote;
            NetherNetClientChannel.this.remoteAddress = remoteAddress;

            promise.setSuccess();

            eventLoop().execute(() -> startHandshake(remoteAddress));
        }
    }
    
    private void startHandshake(InetSocketAddress remoteAddress) {
        try {
            log.debug("Initializing WebRTC native components...");
            if (this.audioDeviceModule == null) {
                this.audioDeviceModule = new HeadlessAudioDeviceModule();
            }
            if (this.factory == null) {
                this.factory = new PeerConnectionFactory(this.audioDeviceModule);
            }

            if (!discovery.isActive()) {
                log.debug("Binding discovery socket...");
                if (this.localAddress != null) {
                    discovery.bind(new InetSocketAddress(this.localAddress.getAddress(), 0));
                } else {
                    discovery.bind(0);
                }
            } else {
                log.debug("Discovery socket already active. Reusing existing transport.");
            }

            log.debug("Creating RTCPeerConnection...");
            RTCConfiguration rtcConfig = new RTCConfiguration();
            rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

            PortAllocatorConfig allocatorConfig = new PortAllocatorConfig();
            allocatorConfig.setDisableAdapterEnumeration(true);
            allocatorConfig.setDisableTcp(true);
            rtcConfig.portAllocatorConfig = allocatorConfig;

            peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    discovery.sendSignal(remoteAddress, 0, 
                        NetherNetConstants.SIGNAL_CANDIDATE_ADD + " " + connectionId + " " + candidate.sdp);
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    log.debug("WebRTC Connection State: {}", state);
                    if (state == RTCPeerConnectionState.FAILED) {
                        close();
                    }
                }

                @Override public void onDataChannel(RTCDataChannel dataChannel) { }
            });

            log.debug("Creating Data Channels...");
            RTCDataChannelInit reliableInit = new RTCDataChannelInit();
            reliableInit.ordered = true;
            reliableInit.protocol = NetherNetConstants.RELIABLE_CHANNEL_LABEL;
            
            RTCDataChannelInit unreliableInit = new RTCDataChannelInit();
            unreliableInit.ordered = false;
            unreliableInit.maxRetransmits = 0;
            
            RTCDataChannel reliable = peerConnection.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, reliableInit);
            RTCDataChannel unreliable = peerConnection.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, unreliableInit);
            
            setDataChannels(reliable, unreliable);

            discovery.registerSignalHandler(connectionId, (signal) -> {
                String[] parts = signal.split(" ", 3);
                if (parts.length < 3) return;
                String type = parts[0];
                String data = parts[2];

                eventLoop().execute(() -> {
                    switch (type) {
                        case NetherNetConstants.SIGNAL_CONNECT_RESPONSE:
                            log.debug("Received CONNECT_RESPONSE (Answer)");
                            peerConnection.setRemoteDescription(
                                new RTCSessionDescription(RTCSdpType.ANSWER, data), 
                                new SetSessionDescriptionObserver() {
                                    @Override public void onSuccess() {}
                                    @Override public void onFailure(String error) {
                                        log.error("RemoteDesc error: {}", error);
                                        close();
                                    }
                                }
                            );
                            break;
                        case NetherNetConstants.SIGNAL_CANDIDATE_ADD:
                            peerConnection.addIceCandidate(new RTCIceCandidate("0", 0, data));
                            break;
                        case NetherNetConstants.SIGNAL_CONNECT_ERROR:
                            log.error("Received CONNECT_ERROR from server: {}", data);
                            close();
                            break;
                    }
                });
            });

            log.debug("Creating Offer...");
            peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            performDiscoveryAndConnect(remoteAddress, description.sdp);
                        }
                        @Override public void onFailure(String error) {
                            log.error("LocalDesc error: {}", error);
                            close();
                        }
                    });
                }
                @Override public void onFailure(String error) {
                    log.error("CreateOffer error: {}", error);
                    close();
                }
            });

        } catch (Exception e) {
            log.error("Handshake initialization failed", e);
            close();
        }
    }

    private void performDiscoveryAndConnect(InetSocketAddress remote, String offerSdp) {
        log.debug("Sending Discovery Request to {}", remote);

        if (handshakeTimeoutTask != null) handshakeTimeoutTask.cancel(false);
        handshakeTimeoutTask = eventLoop().schedule(() -> {
            if (!handshakeComplete) {
                resetAndRetryHandshake();
            }
        }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        discovery.sendDiscoveryRequest(remote, (serverNetworkId, payload) -> {
            try {
                log.debug("Found Server NetworkID: {}", serverNetworkId);
                discovery.sendSignal(
                    remote, 
                    serverNetworkId, 
                    NetherNetConstants.SIGNAL_CONNECT_REQUEST + " " + connectionId + " " + offerSdp
                );
            } finally {
                ReferenceCountUtil.release(payload);
            }
        });
        
        eventLoop().scheduleAtFixedRate(() -> {
            if (!handshakeComplete && reliableChannel != null && reliableChannel.getState() == RTCDataChannelState.OPEN) {
                log.debug("NetherNet Connection Fully Established.");
                handshakeComplete = true;
                pipeline().fireChannelActive();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
}