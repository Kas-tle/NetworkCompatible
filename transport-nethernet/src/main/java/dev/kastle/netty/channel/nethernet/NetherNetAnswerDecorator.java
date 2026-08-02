package dev.kastle.netty.channel.nethernet;

/**
 * Transforms an SDP answer before it is signaled back to the remote peer.
 * The primary use is replacing the transport's built in server identity
 * assertion (the {@code a=identity} attribute, which 26.40 clients require
 * in every answer) with one whose keys and domain the consumer owns and
 * persists.
 *
 * Called from engine threads; implementations must be thread safe and
 * return promptly. A thrown exception makes the channel fall back to the
 * undecorated answer rather than dropping the exchange.
 */
@FunctionalInterface
public interface NetherNetAnswerDecorator {

    /**
     * Returns the answer to actually signal, derived from the negotiated
     * answer.
     *
     * @param answerSdp the negotiated SDP answer
     * @return the SDP answer to signal to the peer
     * @throws Exception if decoration fails; the undecorated answer is used
     */
    String decorate(String answerSdp) throws Exception;
}
