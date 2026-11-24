package com.vab.messaging;

/**
 * Thrown by {@link ResilientMessageHandlerDecorator} when it stops retrying a message <em>without</em>
 * resolving it: the application is shutting down (or the thread was interrupted) while waiting, or the
 * message could not be parked. Propagating it ends that Eventuate subscription without committing the
 * message's offset, so the message is redelivered after the restart instead of being lost.
 */
public class MessageHandlingAbortedException extends RuntimeException {

    public MessageHandlingAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
