package dev.kastle.netty.channel.nethernet;

import dev.kastle.netty.channel.nethernet.config.NetherNetChannelConfig;
import dev.kastle.netty.handler.codec.nethernet.NetherNetDiscovery;
import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.RTCAnswerOptions;
import dev.kastle.webrtc.RTCBundlePolicy;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.EventLoop;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ThreadLocalRandom;

public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final NetherNetChannelConfig config = new NetherNetChannelConfig(this);
    private final PeerConnectionFactory factory;
    
    private NetherNetDiscovery discovery;
    private InetSocketAddress localAddress;
    private long networkId;

    public NetherNetServerChannel() {
        this(new PeerConnectionFactory());
    }

    public NetherNetServerChannel(PeerConnectionFactory factory) {
        this(ThreadLocalRandom.current().nextLong(), factory);
    }

    public NetherNetServerChannel(long networkId, PeerConnectionFactory factory) {
        this.factory = factory;
        this.networkId = networkId;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (!(localAddress instanceof InetSocketAddress)) throw new IllegalArgumentException("Unsupported address type");
        this.localAddress = (InetSocketAddress) localAddress;
        
        this.discovery = new NetherNetDiscovery(this.networkId);
        
        this.discovery.setNewConnectionHandler((connectionId, offerSdp) -> {
            // TODO: extract the sender's network ID from the packet context
            acceptConnection(connectionId, offerSdp, "0");
        });

        this.discovery.bind();
        
        // TODO: Make configurable
        this.discovery.setPongData("NetherNet Server", "World", 1, 0, 10);
    }

    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId) {
        RTCConfiguration rtcConfig = new RTCConfiguration();
        rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

        RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                String candidateString = candidate.sdp;
                discovery.sendSignal(localAddress, Long.parseLong(remoteNetworkId), 
                    NetherNetConstants.SIGNAL_CANDIDATE_ADD + " " + connectionId + " " + candidateString);
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                log.info("Connection {} state changed: {}", connectionId, state);
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                    discovery.unregisterSignalHandler(connectionId);
                }
            }
            
            @Override
            public void onDataChannel(RTCDataChannel dataChannel) {
                // Server accepts channels created by client (handled in NetherNetChannel)
            }
        });

        NetherNetChildChannel child = new NetherNetChildChannel(this, pc, new InetSocketAddress(0), localAddress);
        
        // Register Signal Handler
        discovery.registerSignalHandler(connectionId, (signal) -> {
            String[] parts = signal.split(" ", 3);
            if (parts.length < 3) return;
            
            String type = parts[0];
            String data = parts[2];

            switch (type) {
                case NetherNetConstants.SIGNAL_CANDIDATE_ADD:
                    // Hardcode sdpMid to "0" and sdpMLineIndex to 0 based on NetherNet spec
                    RTCIceCandidate candidate = new RTCIceCandidate("0", 0, data);
                    pc.addIceCandidate(candidate);
                    break;
                case NetherNetConstants.SIGNAL_CONNECT_ERROR:
                    log.error("Connection {} received error: {}", connectionId, data);
                    child.close();
                    break;
            }
        });

        // Handle Offer
        pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, offerSdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription description) {
                        pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                discovery.sendSignal(localAddress, Long.parseLong(remoteNetworkId), 
                                    NetherNetConstants.SIGNAL_CONNECT_RESPONSE + " " + connectionId + " " + description.sdp);
                                
                                pipeline().fireChannelRead(child);
                            }
                            @Override public void onFailure(String error) {
                                log.error("Failed to set local description: {}", error);
                            }
                        });
                    }
                    @Override public void onFailure(String error) {
                        log.error("Failed to create answer: {}", error);
                    }
                });
            }
            @Override public void onFailure(String error) {
                log.error("Failed to set remote description (Offer): {}", error);
            }
        });
    }

    @Override
    protected void doClose() throws Exception {
        if (discovery != null) discovery.close();
        factory.dispose();
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Server channel doesn't read data directly
    }

    @Override
    protected SocketAddress localAddress0() {
        return this.localAddress;
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true; 
    }

    @Override
    public ChannelConfig config() { return config; }
    
    @Override 
    public boolean isOpen() { 
        return discovery != null; 
    }
    
    @Override 
    public boolean isActive() { 
        return isOpen(); 
    }
    
    @Override 
    public ChannelMetadata metadata() { 
        return METADATA; 
    }
}