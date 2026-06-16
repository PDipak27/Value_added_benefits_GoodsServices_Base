package com.vab.events.billing;

/** Success reply to {@code CheckAccountLimitCommand}. */
public class AccountLimitOk {
    private String subscriberId;

    public AccountLimitOk() {}

    public AccountLimitOk(String subscriberId) {
        this.subscriberId = subscriberId;
    }

    public String getSubscriberId() { return subscriberId; }
}
