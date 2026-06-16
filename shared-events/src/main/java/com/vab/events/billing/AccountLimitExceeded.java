package com.vab.events.billing;

/**
 * Failure reply to {@code CheckAccountLimitCommand} — account suspended or the
 * charge exceeds the credit limit. A permanent business failure (no retry).
 */
public class AccountLimitExceeded {
    private String reason;
    private String detail;

    public AccountLimitExceeded() {}

    public AccountLimitExceeded(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
