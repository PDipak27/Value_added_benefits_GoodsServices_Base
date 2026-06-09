package com.vab.events.billing;

/** Success reply to {@code AuthorizeBillingCommand}. */
public class BillingAuthorized {
    private String authId;

    public BillingAuthorized() {}

    public BillingAuthorized(String authId) {
        this.authId = authId;
    }

    public String getAuthId() { return authId; }
}
