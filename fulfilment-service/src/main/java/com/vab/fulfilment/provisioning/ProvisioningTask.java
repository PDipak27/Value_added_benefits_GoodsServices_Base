package com.vab.fulfilment.provisioning;

import com.vab.events.fulfilment.FulfilOrderCommand;
import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.consumer.CommandReplyToken;

import java.time.Instant;

/** One row of {@code fulfilment.provisioning_task} (Design/13 §6). */
public record ProvisioningTask(
        String orderId,
        String commandJson,
        String replyTokenJson,
        Status status,
        boolean cancelRequested,
        String cancelReason,
        boolean ottUncertain,
        int attempts,
        Instant nextAttemptAt,
        Instant deadlineAt,
        String leaseOwner,
        String externalRef) {

    /** AWAITING_ACTIVATION: OTT accepted (202) and will confirm by webhook (Design/06 §8). */
    public enum Status { RETRYING, IN_PROGRESS, AWAITING_ACTIVATION, REVOKE_OWED, CANCEL_TOMBSTONE }

    public FulfilOrderCommand command() {
        return JSonMapper.fromJson(commandJson, FulfilOrderCommand.class);
    }

    public CommandReplyToken replyToken() {
        return JSonMapper.fromJson(replyTokenJson, CommandReplyToken.class);
    }
}
