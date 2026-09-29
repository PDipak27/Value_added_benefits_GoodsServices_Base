package com.vab.billing.command;

import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.messaging.common.Message;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test helper for asserting Eventuate command-handler reply {@link Message}s.
 * A reply carries the outcome ({@code SUCCESS}/{@code FAILURE}) and the reply
 * class name in headers, plus the reply object as a JSON payload.
 */
final class Replies {

    private Replies() {}

    static <T> T payload(Message m, Class<T> type) {
        return JSonMapper.fromJson(m.getPayload(), type);
    }

    static <T> T assertSuccess(Message m, Class<T> replyType) {
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo("SUCCESS");
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE)).isEqualTo(replyType.getName());
        return payload(m, replyType);
    }

    static <T> T assertFailure(Message m, Class<T> replyType) {
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo("FAILURE");
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE)).isEqualTo(replyType.getName());
        return payload(m, replyType);
    }
}
