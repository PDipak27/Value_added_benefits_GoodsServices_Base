package com.vab.paymentsim;

import org.springframework.web.util.HtmlUtils;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;

/**
 * HTML for the hosted checkout ("PaySim"), styled as a mobile payment sheet. Deliberately a different brand
 * from the merchant app: in the demo it should look like a third party. When the page sits in the merchant's
 * iframe it posts the outcome to the parent window, so the app can close its sheet at once.
 */
final class CheckoutPages {

    private CheckoutPages() {}

    enum Outcome { SUCCESS, FAILURE, WARNING }

    static String form(PaymentIntent intent, String clientSecret, String error) {
        String amount = esc(money(intent.getAmount(), intent.getCurrency()));
        long secondsLeft = Math.max(0, Duration.between(Instant.now(), intent.getExpiresAt()).toSeconds());
        String body = """
                <section class="merchant">
                  <div class="m-logo">V</div>
                  <div><div class="m-name">VA-BAGS</div><div class="muted">Order %s</div></div>
                </section>
                <div class="amount">%s</div>
                <div class="muted center">Expires in <b id="left" data-s="%d">%s</b></div>
                <form method="post" action="/checkout/%s/decision" id="pay">
                  <input type="hidden" name="cs" value="%s">
                  <div class="tabs" role="tablist">
                    <button type="button" class="tab on">Card</button>
                    <button type="button" class="tab" disabled title="Card only in this simulator">UPI</button>
                  </div>
                  <div class="card-row">
                    <span class="brand-chip">VISA</span>
                    <div><div><b>•••• 4242</b></div><div class="muted">Test card · expires 12/29</div></div>
                  </div>
                  <label class="otp-label" for="otp">One-time password sent to your phone</label>
                  <input id="otp" name="otp" class="otp" inputmode="numeric" autocomplete="one-time-code"
                         maxlength="6" pattern="[0-9]{6}" placeholder="••••••" autofocus required>
                  %s
                  <button class="pay" name="decision" value="approve">Pay %s</button>
                  <button class="decline" name="decision" value="decline" formnovalidate>Decline payment</button>
                </form>
                <script>
                  const left = document.getElementById('left');
                  let s = +left.dataset.s;
                  setInterval(() => { s = Math.max(0, s - 1); left.textContent = Math.floor(s / 60) + ':' + String(s %% 60).padStart(2, '0'); }, 1000);
                </script>
                """.formatted(esc(intent.getOrderId()), amount, secondsLeft, clock(secondsLeft),
                esc(intent.getId()), esc(clientSecret),
                error == null ? "" : "<div class=\"error\">" + esc(error) + "</div>",
                amount);
        return page("Pay VA-BAGS", body);
    }

    /** A final page; inside an iframe it also tells the merchant app what happened. */
    static String result(Outcome outcome, String title, String text, PaymentIntent intent) {
        String icon = switch (outcome) {
            case SUCCESS -> "<path d=\"M7 12.5l3.2 3.2L17 9\"/>";
            case FAILURE -> "<path d=\"M8.5 8.5l7 7M15.5 8.5l-7 7\"/>";
            case WARNING -> "<path d=\"M12 7.5v5.5M12 16.4v.1\"/>";
        };
        String notify = intent == null ? "" : """
                <script>
                  if (window.parent !== window) {
                    window.parent.postMessage({ source: 'payment-sim', intentId: '%s', status: '%s' }, '*');
                  } else {
                    document.getElementById('close').hidden = false;
                  }
                </script>""".formatted(esc(intent.getId()), intent.getStatus().name());
        String body = """
                <div class="result %s">
                  <div class="r-icon"><svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor"
                       stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">%s</svg></div>
                  <h1>%s</h1>
                  <p class="muted">%s</p>
                  %s
                  <button class="pay" id="close" hidden onclick="window.close()">Close</button>
                </div>
                %s
                """.formatted(outcome.name().toLowerCase(), icon, esc(title), esc(text),
                intent == null ? "" : "<div class=\"receipt\"><span>VA-BAGS</span><b>"
                        + esc(money(intent.getAmount(), intent.getCurrency())) + "</b></div>",
                notify);
        return page(title, body);
    }

    private static String page(String title, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>%s</title>
                <style>%s</style></head>
                <body><main>
                  <header><div class="ps-logo"><span>P</span>PaySim</div><span class="badge">TEST MODE</span></header>
                  %s
                  <footer><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>
                  Secured by PaySim · payment provider simulator</footer>
                </main></body></html>
                """.formatted(esc(title), CSS, body);
    }

    private static final String CSS = """
            :root { --ink:#0b1f33; --muted:#5d6b7c; --line:#e3e8ef; --accent:#00a37a; --accent-2:#0b8f6b; --bg:#f4f7fa; --card:#fff; --bad:#d23b2f; --warn:#c27803; }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
            main { max-width: 420px; margin: 0 auto; min-height: 100vh; padding: 16px 18px 24px; display: flex; flex-direction: column; gap: 14px; }
            header { display: flex; justify-content: space-between; align-items: center; }
            .ps-logo { display: flex; align-items: center; gap: 8px; font-weight: 800; letter-spacing: -.01em; }
            .ps-logo span { width: 26px; height: 26px; border-radius: 8px; display: grid; place-items: center; color: #fff; background: linear-gradient(135deg, var(--accent), #0a6fd6); }
            .badge { font-size: 11px; font-weight: 700; letter-spacing: .04em; color: var(--warn); background: #fff4dc; padding: 4px 8px; border-radius: 999px; }
            .merchant { display: flex; gap: 12px; align-items: center; background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 12px; }
            .m-logo { width: 38px; height: 38px; border-radius: 11px; display: grid; place-items: center; color: #fff; font-weight: 800; background: linear-gradient(135deg, #4338ca, #7c3aed); }
            .m-name { font-weight: 700; }
            .muted { color: var(--muted); font-size: 13px; }
            .center { text-align: center; }
            .amount { text-align: center; font-size: 40px; font-weight: 800; letter-spacing: -.02em; margin-top: 4px; }
            form { display: flex; flex-direction: column; gap: 12px; background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 14px; }
            .tabs { display: grid; grid-template-columns: 1fr 1fr; background: var(--bg); border-radius: 12px; padding: 4px; gap: 4px; }
            .tab { border: 0; border-radius: 9px; padding: 8px; font: inherit; font-weight: 650; background: none; color: var(--muted); }
            .tab.on { background: var(--card); color: var(--ink); box-shadow: 0 1px 3px rgba(11,31,51,.12); }
            .card-row { display: flex; gap: 12px; align-items: center; border: 1.5px solid var(--accent); background: #ecfaf5; border-radius: 12px; padding: 10px 12px; }
            .brand-chip { font: italic 800 13px/1 system-ui; color: #1a1f71; background: #fff; border: 1px solid var(--line); border-radius: 6px; padding: 6px 7px; }
            .otp-label { font-size: 13px; font-weight: 600; }
            .otp { font: 600 26px/1 ui-monospace, Menlo, Consolas, monospace; letter-spacing: .5em; text-align: center; padding: 12px 10px 12px calc(10px + .5em); border: 1.5px solid var(--line); border-radius: 12px; width: 100%; color: var(--ink); background: var(--card); }
            .otp:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 4px #d6f5ea; }
            .link { border: 0; background: none; color: var(--accent-2); font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; align-self: center; }
            .pay { border: 0; border-radius: 13px; padding: 14px; font: inherit; font-weight: 750; font-size: 16px; color: #fff; background: linear-gradient(135deg, var(--accent), var(--accent-2)); cursor: pointer; width: 100%; }
            .decline { border: 0; background: none; color: var(--bad); font: inherit; font-weight: 600; cursor: pointer; }
            .error { color: var(--bad); background: #fdecea; border-radius: 10px; padding: 8px 10px; font-size: 13px; font-weight: 600; }
            .result { text-align: center; background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 28px 18px 20px; display: flex; flex-direction: column; align-items: center; gap: 6px; }
            .result h1 { margin: 8px 0 0; font-size: 21px; }
            .result p { margin: 0 0 8px; }
            .r-icon { width: 72px; height: 72px; border-radius: 50%; display: grid; place-items: center; color: #fff; animation: pop .35s cubic-bezier(.2,1.4,.4,1); }
            .success .r-icon { background: var(--accent); } .failure .r-icon { background: var(--bad); } .warning .r-icon { background: var(--warn); }
            .receipt { display: flex; justify-content: space-between; width: 100%; border-top: 1px dashed var(--line); padding-top: 12px; margin-top: 6px; color: var(--muted); }
            .receipt b { color: var(--ink); }
            footer { margin-top: auto; display: flex; gap: 6px; justify-content: center; align-items: center; color: var(--muted); font-size: 12px; }
            [hidden] { display: none !important; }
            @keyframes pop { from { transform: scale(.4); opacity: 0; } }
            """;

    static String money(long amount, String currency) {
        try {
            NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
            f.setCurrency(Currency.getInstance(currency));
            f.setMaximumFractionDigits(0);
            return f.format(amount);
        } catch (RuntimeException e) {
            return amount + " " + currency;
        }
    }

    private static String clock(long seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }
}
