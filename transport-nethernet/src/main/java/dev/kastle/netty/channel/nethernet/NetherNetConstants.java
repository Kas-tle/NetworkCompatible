package dev.kastle.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

public class NetherNetConstants {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetConstants.class);
    
    public static final int DISCOVERY_PORT = 7551;
    public static final long APPLICATION_ID = 0xDEADBEEFL;
    
    // Packet IDs
    public static final int ID_DISCOVERY_REQUEST = 0x00;
    public static final int ID_DISCOVERY_RESPONSE = 0x01;
    public static final int ID_DISCOVERY_MESSAGE = 0x02;

    // Signaling Message Types
    public static final String SIGNAL_CONNECT_REQUEST = "CONNECTREQUEST";
    public static final String SIGNAL_CONNECT_RESPONSE = "CONNECTRESPONSE";
    public static final String SIGNAL_CANDIDATE_ADD = "CANDIDATEADD";
    public static final String SIGNAL_CONNECT_ERROR = "CONNECTERROR";

    // SCTP Constants
    public static final int MAX_SCTP_MESSAGE_SIZE = 10000;
    public static final String RELIABLE_CHANNEL_LABEL = "ReliableDataChannel";
    public static final String UNRELIABLE_CHANNEL_LABEL = "UnreliableDataChannel";

    private static final byte[] KEY_BYTES;

    static {
        try {
            ByteBuf buf = Unpooled.buffer(8);
            buf.writeLongLE(APPLICATION_ID);
            byte[] input = new byte[8];
            buf.readBytes(input);
            buf.release();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            KEY_BYTES = digest.digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] encryptDiscoveryPacket(ByteBuf packet) throws Exception {
        int len = packet.readableBytes() + 2;
        ByteBuf payload = Unpooled.buffer(len);
        payload.writeShortLE(len);
        payload.writeBytes(packet);
        
        byte[] payloadBytes = new byte[payload.readableBytes()];
        payload.readBytes(payloadBytes);
        payload.release();

        SecretKeySpec secretKey = new SecretKeySpec(KEY_BYTES, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(payloadBytes);

        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(KEY_BYTES, "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] signature = sha256_HMAC.doFinal(payloadBytes);

        ByteBuf result = Unpooled.buffer(signature.length + encrypted.length);
        result.writeBytes(signature);
        result.writeBytes(encrypted);
        
        byte[] out = new byte[result.readableBytes()];
        result.readBytes(out);
        result.release();
        return out;
    }

    public static ByteBuf decryptDiscoveryPacket(ByteBuf input) throws Exception {
        if (input.readableBytes() < 32) {
            log.debug("Discovery packet too short to contain valid signature");
            return null;
        };

        byte[] signature = new byte[32];
        input.readBytes(signature);

        byte[] encrypted = new byte[input.readableBytes()];
        input.readBytes(encrypted);

        SecretKeySpec secretKey = new SecretKeySpec(KEY_BYTES, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] payloadBytes = cipher.doFinal(encrypted);

        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(KEY_BYTES, "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] calculatedSignature = sha256_HMAC.doFinal(payloadBytes);

        if (!MessageDigest.isEqual(signature, calculatedSignature)) {
            log.debug("Invalid discovery packet signature");
            return null;
        }

        ByteBuf payload = Unpooled.wrappedBuffer(payloadBytes);
        payload.readUnsignedShortLE(); // Length prefix

        return payload;
    }
}