// VA-BAGS demo app: a phone-style storefront over the real platform. Plain JS, no build step.
// Talks only to the gateway (same origin) plus Keycloak for login (Authorization Code + PKCE, public client
// "vab-web") and shows payment-sim's checkout page inside the phone. Tokens live in sessionStorage.
'use strict';

const $ = (id) => document.getElementById(id);
const store = sessionStorage;
let cfg = null;

// ── OIDC: Authorization Code + PKCE ─────────────────────────────────────────

const oidc = {
  redirectUri: location.origin + '/ui/index.html',
  endpoint(name) { return `${cfg.issuer}/protocol/openid-connect/${name}`; },

  async login() {
    const verifier = randomString(64);
    const state = randomString(24);
    store.setItem('pkce_verifier', verifier);
    store.setItem('pkce_state', state);
    store.setItem('return_to', location.hash || '#/store');
    const params = new URLSearchParams({
      client_id: cfg.clientId, response_type: 'code', scope: 'openid',
      redirect_uri: this.redirectUri, state,
      code_challenge: await sha256Base64Url(verifier), code_challenge_method: 'S256',
    });
    location.assign(`${this.endpoint('auth')}?${params}`);
  },

  /** Back from Keycloak with ?code=…: swap it for tokens, then drop the query from the address bar. */
  async completeLoginIfReturning() {
    const q = new URLSearchParams(location.search);
    if (!q.has('code') && !q.has('error')) return;
    history.replaceState(null, '', this.redirectUri + (store.getItem('return_to') || '#/store'));
    if (q.has('error')) return toast(`Sign-in failed: ${q.get('error_description') || q.get('error')}`);
    if (q.get('state') !== store.getItem('pkce_state')) return toast('Sign-in failed: state mismatch');
    await this.tokenRequest({
      grant_type: 'authorization_code', code: q.get('code'),
      redirect_uri: this.redirectUri, code_verifier: store.getItem('pkce_verifier'),
    });
    store.removeItem('pkce_verifier');
    store.removeItem('pkce_state');
    const c = this.claims();
    feed.event(`Signed in as ${c?.preferred_username} — Keycloak, Authorization Code + PKCE`);
  },

  async tokenRequest(form) {
    const res = await fetch(this.endpoint('token'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: cfg.clientId, ...form }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      this.clear();
      throw new Error(body.error_description || body.error || `token endpoint ${res.status}`);
    }
    const now = Date.now();
    store.setItem('tokens', JSON.stringify({
      access: body.access_token, refresh: body.refresh_token, id: body.id_token,
      accessExp: now + body.expires_in * 1000,
      refreshExp: now + (body.refresh_expires_in || 0) * 1000,
    }));
  },

  tokens() { return JSON.parse(store.getItem('tokens') || 'null'); },
  clear() { store.removeItem('tokens'); },

  /** A valid access token, refreshed when it has under 30 s left; null when signed out. */
  async accessToken() {
    const t = this.tokens();
    if (!t) return null;
    if (Date.now() < t.accessExp - 30_000) return t.access;
    if (!t.refresh || (t.refreshExp && Date.now() > t.refreshExp)) { this.clear(); renderChrome(); return null; }
    try {
      await this.tokenRequest({ grant_type: 'refresh_token', refresh_token: t.refresh });
      feed.event('Access token refreshed');
      return this.tokens().access;
    } catch (e) {
      toast(`Session expired: ${e.message}`);
      renderChrome();
      return null;
    }
  },

  claims() {
    const t = this.tokens();
    return t ? JSON.parse(base64UrlDecode(t.access.split('.')[1])) : null;
  },

  logout() {
    const t = this.tokens();
    this.clear();
    const params = new URLSearchParams({ client_id: cfg.clientId, post_logout_redirect_uri: this.redirectUri });
    if (t?.id) params.set('id_token_hint', t.id);
    location.assign(`${this.endpoint('logout')}?${params}`);
  },
};

function randomString(len) {
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  return Array.from(bytes, (b) => 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~'[b % 66]).join('');
}
async function sha256Base64Url(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return btoa(String.fromCharCode(...new Uint8Array(digest))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function base64UrlDecode(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  return decodeURIComponent(escape(atob(b64)));
}
const signedIn = () => !!oidc.tokens();
const isAdmin = () => (oidc.claims()?.realm_access?.roles || []).includes('vab-admin');

// ── API (through the gateway) ──────────────────────────────────────────────

async function api(method, path, { body, headers = {}, auth = true, quiet = false } = {}) {
  const h = { Accept: 'application/json', ...headers };
  if (auth) {
    const token = await oidc.accessToken();
    if (!token) throw new Error('Please sign in first');
    h.Authorization = `Bearer ${token}`;
  }
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const started = performance.now();
  let res;
  try {
    res = await fetch(path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    feed.call(method, path, 'ERR', Math.round(performance.now() - started), body, 'network error', headers);
    throw new Error('Can’t reach VA-BAGS right now. Check your connection and try again.');
  }
  const text = await res.text();
  let data = text;
  try { data = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  if (!quiet || !res.ok) feed.call(method, path, res.status, Math.round(performance.now() - started), body, data, headers);
  if (!res.ok) {
    const msg = (data && (data.detail || data.message || data.error)) || res.statusText || 'request failed';
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return data;
}

// ── domain helpers ──────────────────────────────────────────────────────────

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELLED_REFUNDED'];
const catalog = { offers: [], byCode: new Map() };
const DEMO = /DEMO|OTTBAD|OTTDOWN/;

const TYPES = {
  DIGITAL_SUBSCRIPTION: { label: 'Streaming', deliver: 'Subscription activated', delivering: 'Activating with the streaming partner…',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9.5v5l4.5-2.5z" fill="currentColor"/></svg>' },
  SOFTWARE_LICENSE: { label: 'Software', deliver: 'Licence key issued', delivering: 'Issuing your licence key…',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="8" cy="15" r="4"/><path d="M11 12l8-8M16 7l2 2M14 9l2 2"/></svg>' },
  PHYSICAL_GOOD: { label: 'Devices', deliver: 'Shipped', delivering: 'Packing and handing to the courier…',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M3 7.5L12 3l9 4.5v9L12 21l-9-4.5z M3 7.5L12 12l9-4.5 M12 12v9"/></svg>' },
};
const GRADIENTS = [
  ['#4338ca', '#7c3aed'], ['#0e7490', '#2563eb'], ['#be185d', '#f97316'], ['#047857', '#0ea5e9'],
  ['#7c2d12', '#ea580c'], ['#1e3a8a', '#9333ea'], ['#b91c1c', '#db2777'], ['#0f766e', '#65a30d'],
];

function typeOf(productType) { return TYPES[productType] || TYPES.PHYSICAL_GOOD; }
function gradient(code) {
  let h = 0;
  for (const ch of String(code)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const [a, b] = GRADIENTS[h % GRADIENTS.length];
  return `linear-gradient(135deg, ${a}, ${b})`;
}
function offerName(code) { return catalog.byCode.get(code)?.name || code; }
function money(amount, currency) {
  try {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: currency || 'INR', maximumFractionDigits: 0 }).format(amount);
  } catch { return `${amount} ${currency ?? ''}`; }
}
function shortId(id) { return id ? `#${id.replace(/^ord_/, '').slice(0, 6).toUpperCase()}` : '—'; }
function when(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const sameDay = d.toDateString() === new Date().toDateString();
  return sameDay ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
}

/** PAY_NOW vs BILL_TO_MOBILE isn't on the order view: remember our own choice, else infer from the timeline. */
function billingModeOf(order) {
  try {
    const remembered = JSON.parse(localStorage.getItem('modes') || '{}')[order.orderId];
    if (remembered) return remembered;
  } catch { /* storage blocked */ }
  return (order.payment || (order.timeline || []).some((t) => t.status === 'PENDING_PAYMENT')) ? 'PAY_NOW' : 'BILL_TO_MOBILE';
}
function rememberMode(orderId, mode) {
  try {
    const m = JSON.parse(localStorage.getItem('modes') || '{}');
    m[orderId] = mode;
    localStorage.setItem('modes', JSON.stringify(m));
  } catch { /* storage blocked */ }
}

function statusChip(status) {
  const map = {
    PLACED: ['Processing', 't-info', true], PENDING_PAYMENT: ['Awaiting payment', 't-warn', true],
    CONFIRMED: ['Confirmed', 't-info', true], COMPLETED: ['Completed', 't-ok'],
    FAILED: ['Failed', 't-bad'], CANCELLED: ['Cancelled', 't-bad'], CANCELLED_REFUNDED: ['Refunded', 't-warn'],
    ACTIVE: ['Active', 't-ok'], REVOKED: ['Revoked', 't-bad'],
  };
  const [label, tone, live] = map[status] || [status, 't-info'];
  return el('span', label, `status ${tone}${live ? ' live' : ''}`);
}

function thumb(code, productType, cls = 'thumb') {
  const t = el('div', null, cls);
  t.style.background = gradient(code);
  t.innerHTML = typeOf(productType).icon;   // constant, trusted markup
  return t;
}

// ── router ──────────────────────────────────────────────────────────────────

let poller = null;
let refreshTracker = null;
function stopPolling() { clearInterval(poller); poller = null; refreshTracker = null; }

const routes = [
  [/^#\/store$/, () => screens.store()],
  [/^#\/orders$/, () => screens.orders()],
  [/^#\/orders\/([\w-]+)$/, (m) => screens.order(m[1])],
  [/^#\/benefits$/, () => screens.benefits()],
  [/^#\/profile$/, () => screens.profile()],
];

function route() {
  stopPolling();
  closeSheet();
  const hash = location.hash || '#/store';
  for (const [re, handler] of routes) {
    const m = hash.match(re);
    if (m) {
      const tab = hash.split('/')[1];
      document.querySelectorAll('.tabbar a').forEach((a) => {
        if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
      });
      handler(m);
      return;
    }
  }
  location.hash = '#/store';
}

/** Swaps the screen content; {@code title} shows a back button and a heading instead of the brand. */
function show(nodes, { title, back } = {}) {
  const screen = $('screen');
  screen.replaceChildren(...[].concat(nodes));
  screen.classList.remove('enter');
  void screen.offsetWidth;
  screen.classList.add('enter');
  screen.scrollTop = 0;
  $('brand').hidden = !!title;
  $('screenTitle').hidden = !title;
  $('screenTitle').textContent = title || '';
  $('backBtn').hidden = !back;
  $('backBtn').onclick = () => { location.hash = back; };
}

// ── screens ─────────────────────────────────────────────────────────────────

const screens = {
  async store() {
    const name = oidc.claims()?.given_name || oidc.claims()?.preferred_username;
    const hero = el('section', [
      el('small', name ? `Hi ${capitalise(name)}, good to see you` : 'New this week'),
      el('h2', 'Stream, work and play — all in one place'),
      el('div', [el('span', 'Pay in seconds', 'pill'), el('span', 'Or add it to your phone bill', 'pill')], 'hero-row'),
    ], 'hero');
    const filters = [['all', 'All'], ['DIGITAL_SUBSCRIPTION', 'Streaming'], ['SOFTWARE_LICENSE', 'Software'],
      ['PHYSICAL_GOOD', 'Devices'], ['demo', 'Scenarios']];
    let current = sessionStorage.getItem('filter') || 'all';
    const chips = el('div', filters.map(([key, label]) => {
      const b = el('button', label, 'chip');
      b.setAttribute('aria-pressed', String(key === current));
      b.onclick = () => {
        current = key;
        sessionStorage.setItem('filter', key);
        chips.querySelectorAll('.chip').forEach((c) => c.setAttribute('aria-pressed', String(c === b)));
        paint();
      };
      return b;
    }), 'chips');
    const grid = el('div', el('div', [el('div', null, 'spinner')], 'empty'), 'grid');
    show([hero, chips, grid]);

    function paint() {
      const offers = catalog.offers.filter((o) => current === 'all' ? !DEMO.test(o.offerCode)
        : current === 'demo' ? DEMO.test(o.offerCode) : o.productType === current && !DEMO.test(o.offerCode));
      if (!offers.length) {
        grid.replaceChildren(el('div', 'Nothing here yet.', 'empty'));
        grid.style.display = 'block';
        return;
      }
      grid.style.display = '';
      grid.replaceChildren(...offers.map(offerCard));
    }

    try {
      await loadCatalog();
      paint();
    } catch (e) {
      grid.style.display = 'block';
      grid.replaceChildren(el('div', [el('h3', 'Store unavailable'), el('div', e.message)], 'empty'));
    }
  },

  async orders() {
    if (!signedIn()) return show(signInPrompt('Your orders live here', 'Sign in to place orders and follow them live.'));
    const list = el('div', el('div', [el('div', null, 'spinner')], 'empty'), 'list');
    show([el('div', [el('h3', 'Your orders'), el('span', 'Updates live')], 'section-title'), list]);
    let drawn = null;
    const paint = async () => {
      const orders = await api('GET', '/v1/orders', { quiet: true });
      watchStatuses(orders, true);
      const snapshot = JSON.stringify(orders);
      if (snapshot === drawn) return orders.some((o) => !TERMINAL.includes(o.status));
      drawn = snapshot;
      if (!orders?.length) {
        list.replaceChildren(empty('No orders yet', 'Pick something in the Store to get started.'));
        return false;
      }
      list.replaceChildren(...orders.map(orderRow));
      return orders.some((o) => !TERMINAL.includes(o.status));
    };
    try {
      await loadCatalog().catch(() => {});
      if (await paint()) poller = setInterval(() => paint().then((live) => { if (!live) stopPolling(); }).catch(() => {}), 2500);
    } catch (e) {
      list.replaceChildren(empty('Could not load orders', e.message));
    }
  },

  async order(orderId) {
    if (!signedIn()) return show(signInPrompt('Sign in to see this order', ''), { title: 'Order', back: '#/orders' });
    const body = el('div', el('div', [el('div', null, 'spinner')], 'empty'));
    show(body, { title: `Order ${shortId(orderId)}`, back: '#/orders' });
    await loadCatalog().catch(() => {});
    let autoOpened = false;
    let drawn = null;
    const paint = async () => {
      const order = await api('GET', `/v1/orders/${orderId}`, { quiet: true });
      watchStatuses([order]);
      const snapshot = JSON.stringify(order);
      if (snapshot !== drawn) {   // redraw only on change: no flicker, buttons stay put
        drawn = snapshot;
        body.replaceChildren(...trackerView(order));
      }
      // Demo flow: open the checkout as soon as the provider has a payment page for this order.
      if (order.status === 'PENDING_PAYMENT' && order.payment?.checkoutUrl && !autoOpened && justPlaced === orderId) {
        autoOpened = true;
        openCheckout(order);
      }
      if (order.status !== 'PENDING_PAYMENT' && sheetFor === `checkout:${orderId}`) {
        // The backend moved on (also covers a provider page that doesn't post its result).
        closeCheckoutSoon(3000, ['CONFIRMED', 'COMPLETED'].includes(order.status)
          ? 'Payment received — thank you!' : 'The payment didn’t go through');
      }
      return !TERMINAL.includes(order.status);
    };
    const tick = () => paint().then((live) => { if (!live) stopPolling(); }).catch(() => {});
    refreshTracker = tick;
    try {
      if (await paint()) poller = setInterval(tick, 1500);
    } catch (e) {
      body.replaceChildren(empty('Order not found', e.message));
    }
  },

  async benefits() {
    if (!signedIn()) return show(signInPrompt('Your subscriptions and licences', 'Sign in to see what you own.'));
    const list = el('div', el('div', [el('div', null, 'spinner')], 'empty'), 'list');
    show([el('div', [el('h3', 'Your benefits'), el('span', 'Streaming and software')], 'section-title'), list]);
    try {
      await loadCatalog().catch(() => {});
      const items = await api('GET', '/v1/entitlements', { quiet: true });
      if (!items?.length) {
        list.replaceChildren(empty('No benefits yet', 'Streaming plans and software licences you buy appear here.'));
        return;
      }
      list.replaceChildren(...items.map((e) => {
        const ref = e.activationKey || e.externalRef;
        return el('div', [
          el('div', [thumb(e.offerCode, e.productType), el('div', [
            el('div', offerName(e.offerCode), 'row-title'),
            el('div', e.validUntil ? `Valid until ${when(e.validUntil)}` : 'Never expires', 'row-sub'),
          ], 'row-main'), statusChip(e.status)], 'row-card'),
          ref ? copyable(e.productType === 'SOFTWARE_LICENSE' ? 'Licence key' : 'Subscription ref', ref) : null,
        ].filter(Boolean), 'card');
      }));
    } catch (e) {
      list.replaceChildren(empty('Could not load benefits', e.message));
    }
  },

  profile() {
    const c = oidc.claims();
    if (!c) return show([signInPrompt('Your VA-BAGS account', 'One sign-in for the store, your orders and the streaming partner.'), demoGuide()]);
    const roles = (c.realm_access?.roles || []).filter((r) => r.startsWith('vab-'));
    const me = el('div', [
      el('div', [
        el('div', initials(c), 'avatar big'),
        el('div', [el('div', c.name || c.preferred_username, 'row-title'), el('div', c.email || c.preferred_username, 'row-sub')], 'row-main'),
      ], 'row-card'),
      kv([['Subscriber ID', c.subscriberId ?? '—'], ['Roles', roles.length ? roles.join(', ') : 'subscriber'],
        ['Signed in via', 'Keycloak · PKCE']]),
    ], 'card');
    const inspectorToggle = el('label', [
      el('input'), el('div', [el('b', 'Show “under the hood” panel'), el('small', 'Live API calls and order events beside the phone (wide screens).')]),
    ]);
    const input = inspectorToggle.querySelector('input');
    input.type = 'checkbox';
    input.checked = inspectorVisible();
    input.onchange = () => setInspector(input.checked);
    const out = el('button', 'Sign out', 'btn danger');
    out.onclick = () => oidc.logout();
    show([me, el('div', inspectorToggle, 'choice'), demoGuide(), out]);
  },
};

// ── store pieces ────────────────────────────────────────────────────────────

async function loadCatalog() {
  if (catalog.offers.length) return;
  const offers = await api('GET', '/v1/offers', { auth: false });
  catalog.offers = offers || [];
  catalog.byCode = new Map(catalog.offers.map((o) => [o.offerCode, o]));
}

function offerCard(o) {
  const art = el('div', null, 'offer-art');
  art.style.background = gradient(o.offerCode);
  art.insertAdjacentHTML('beforeend', typeOf(o.productType).icon);
  const card = el('button', [art, el('div', [
    el('div', o.name, 'offer-name'),
    el('div', typeOf(o.productType).label, 'offer-type'),
    el('div', [el('span', o.offerCode, 'offer-code'), el('span', money(o.amount, o.currency), 'offer-price')], 'offer-meta'),
  ], 'offer-body')], 'offer');
  card.onclick = () => openOffer(o);
  return card;
}

async function openOffer(summary) {
  const body = el('div', el('div', [el('div', null, 'spinner')], 'empty'));
  openSheet(body, `offer:${summary.offerCode}`);
  let detail = summary;
  try { detail = { ...summary, ...(await api('GET', `/v1/offers/${summary.offerCode}`, { auth: false, quiet: true })) }; } catch { /* summary is enough */ }

  const art = el('div', el('span', typeOf(detail.productType).label, 'tag'), 'offer-art');
  art.style.cssText = `background:${gradient(detail.offerCode)};height:130px;border-radius:18px`;
  art.insertAdjacentHTML('beforeend', typeOf(detail.productType).icon);
  const term = detail.termMonths ? `${detail.termMonths} month${detail.termMonths > 1 ? 's' : ''}` : null;

  const choice = el('div', [
    radio('mode', 'PAY_NOW', 'Pay now', 'Card or UPI on a secure checkout page', true),
    radio('mode', 'BILL_TO_MOBILE', 'Add to my mobile bill', 'Pay with your next monthly bill', false),
  ], 'choice');
  const buy = el('button', signedIn() ? `Pay ${money(detail.amount, detail.currency)}` : 'Sign in to buy', 'btn');
  choice.addEventListener('change', () => {
    const mode = choice.querySelector('input:checked').value;
    if (signedIn()) buy.textContent = mode === 'PAY_NOW' ? `Pay ${money(detail.amount, detail.currency)}` : 'Add to my bill';
  });
  const problem = el('div');   // inline error slot: a sheet covers the toast area, so errors show here
  buy.onclick = () => {
    if (!signedIn()) return oidc.login();
    problem.replaceChildren();
    run(buy, () => placeOrder(detail, choice.querySelector('input:checked').value),
      (e) => problem.replaceChildren(orderError(e)));
  };

  const owned = signedIn() && detail.productType !== 'PHYSICAL_GOOD' && (await ownedOffers()).has(detail.offerCode);
  const benefitsBtn = el('a', 'View in Benefits', 'btn');
  benefitsBtn.href = '#/benefits';

  body.replaceChildren(...[
    art,
    el('div', [el('h2', detail.name), el('div', detail.description || typeOf(detail.productType).label, 'hint')]),
    kv([['Price', money(detail.amount, detail.currency)], term ? ['Term', term] : null, ['Delivered as', typeOf(detail.productType).deliver]].filter(Boolean)),
    owned ? banner('t-ok', 'You already have this', 'It’s active in your Benefits, so there’s nothing to buy.') : choice,
    problem,
    owned ? benefitsBtn : buy,
    DEMO.test(detail.offerCode) ? el('div', scenarioHint(detail.offerCode), 'hint') : null,
  ].filter(Boolean));
}

/** Offer codes with an ACTIVE entitlement; cached briefly so opening sheets stays snappy. */
let ownedCache = { at: 0, codes: new Set() };
async function ownedOffers() {
  if (Date.now() - ownedCache.at < 15_000) return ownedCache.codes;
  try {
    const items = await api('GET', '/v1/entitlements', { quiet: true });
    ownedCache = { at: Date.now(), codes: new Set((items || []).filter((e) => e.status === 'ACTIVE').map((e) => e.offerCode)) };
  } catch { /* unknown: let the server decide */ }
  return ownedCache.codes;
}

/** Placement failures in words a customer understands, with a way forward where there is one. */
function orderError(e) {
  if (e.status === 409 && /entitled/i.test(e.message)) {
    const go = el('a', 'View in Benefits', 'text-btn');
    go.href = '#/benefits';
    ownedCache.at = 0;
    return banner('t-warn', 'You already have this', 'Your subscription is still active.', go);
  }
  if (e.status === 409) return banner('t-warn', 'The price has changed', `${e.message}. Close this and open the offer again.`);
  if (e.status === 503) return banner('t-bad', 'We can’t take orders right now', `${e.message}. Please try again in a moment.`);
  return banner('t-bad', 'Couldn’t place the order', e.message);
}

function banner(tone, title, text, action) {
  return el('div', el('div', [el('b', title), text, action ? el('div', action) : null]), `banner ${tone}`);
}

function radio(name, value, title, sub, checked) {
  const input = el('input');
  input.type = 'radio';
  input.name = name;
  input.value = value;
  input.checked = checked;
  return el('label', [input, el('div', [el('b', title), el('small', sub)])]);
}

let justPlaced = null;

async function placeOrder(offer, billingMode) {
  const res = await api('POST', '/v1/orders', {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: {
      offerCode: offer.offerCode, productType: offer.productType, priceSnapshotId: offer.priceSnapshotId,
      amount: offer.amount, currency: offer.currency, billingMode,
    },
  });
  rememberMode(res.orderId, billingMode);
  justPlaced = res.orderId;
  feed.event(`Order ${shortId(res.orderId)} placed (${billingMode}) — saga started`);
  closeSheet();
  location.hash = `#/orders/${res.orderId}`;
}

// ── order tracker ───────────────────────────────────────────────────────────

function orderRow(o) {
  const a = el('a', [
    thumb(o.offerCode, o.productType),
    el('div', [
      el('div', offerName(o.offerCode), 'row-title'),
      el('div', [statusChip(o.status), el('span', `${shortId(o.orderId)} · ${when(o.placedAt)}`)], 'row-sub row-meta'),
    ], 'row-main'),
    el('div', [el('b', money(o.amount, o.currency)), el('span', chargeLabel(o), 'row-sub')], 'row-end'),
  ], 'card row-card');
  a.href = `#/orders/${o.orderId}`;
  return a;
}

/** How the order was (or wasn't) charged, in a couple of words. */
function chargeLabel(o) {
  const payNow = billingModeOf(o) === 'PAY_NOW';
  switch (o.status) {
    case 'FAILED':
    case 'CANCELLED': return 'Not charged';
    case 'CANCELLED_REFUNDED': return payNow ? 'Refunded' : 'Removed from bill';
    case 'PLACED':
    case 'PENDING_PAYMENT': return payNow ? 'Pay now' : 'Mobile bill';
    default: return payNow ? 'Paid' : 'On mobile bill';
  }
}

function trackerView(o) {
  const type = typeOf(o.productType);
  const mode = billingModeOf(o);
  const seen = new Set((o.timeline || []).map((t) => t.status));
  const at = (s) => (o.timeline || []).find((t) => t.status === s)?.at;

  const head = el('div', [
    thumb(o.offerCode, o.productType),
    el('div', [
      el('div', offerName(o.offerCode), 'row-title wrap'),
      el('div', [statusChip(o.status), el('span', mode === 'PAY_NOW' ? 'Card / UPI' : 'Mobile bill')], 'row-sub row-meta'),
    ], 'row-main'),
    el('div', money(o.amount, o.currency), 'row-end big-price'),
  ], 'card track-head');

  // Progress: placed → payment → confirmed → delivered.
  const reached = { PLACED: 1, PENDING_PAYMENT: 1, CONFIRMED: 3, COMPLETED: 4 }[o.status];
  const failedAt = o.status === 'FAILED' ? (seen.has('CONFIRMED') ? 3 : 1) : -1;
  const stopped = TERMINAL.includes(o.status) && o.status !== 'COMPLETED';
  const steps = [
    ['Order placed', when(o.placedAt)],
    mode === 'PAY_NOW'
      ? ['Payment', o.status === 'PLACED' ? 'Preparing secure checkout…'
        : o.status === 'PENDING_PAYMENT' ? (paidAt.has(o.orderId) ? 'Authorised by the payment provider — confirming…'
          : `Waiting for you — pay before ${when(o.payment?.expiresAt)}`) : seen.has('CONFIRMED') ? 'Paid' : '']
      : ['Billing', o.status === 'PLACED' ? 'Checking your mobile account…' : seen.has('CONFIRMED') ? 'Added to your next bill' : ''],
    ['Confirmed', o.confirmedAt ? `${when(o.confirmedAt)} · stock reserved, charge settled` : 'Stock reserved, charge settled'],
    [type.deliver, o.status === 'COMPLETED' ? when(o.completedAt) : o.status === 'CONFIRMED' ? type.delivering : ''],
  ];
  const stepList = el('ol', steps.map(([title, sub], i) => {
    let state = '';
    if (!stopped) state = i < reached ? 'done' : i === reached ? 'active' : '';
    else if (i === failedAt) state = 'failed';
    else if (i === 0 || (seen.has('CONFIRMED') && i <= 2)) state = 'done';
    const dot = el('div', state === 'done' ? '✓' : state === 'failed' ? '!' : String(i + 1), 'step-dot');
    return el('li', [dot, el('div', [el('div', title, 'step-title'), sub ? el('div', sub, 'step-sub') : null].filter(Boolean))], state);
  }), 'steps');

  const parts = [head];
  const outcome = outcomeBanner(o, mode);
  if (outcome) parts.push(outcome);
  parts.push(el('div', stepList, 'card'));

  if (o.status === 'COMPLETED' && o.fulfilment) {
    const f = o.fulfilment;
    const ref = f.trackingRef || f.activationKey || f.externalRef;
    const label = f.trackingRef ? 'Courier tracking number' : f.activationKey ? 'Licence key' : 'Subscription ref';
    if (ref) parts.push(el('div', copyable(label, ref), 'card'));
  }

  const actions = [];
  if (o.status === 'PENDING_PAYMENT' && paidAt.has(o.orderId)) {
    // The provider said "authorised"; the webhook → billing → saga hop takes a moment.
    const wait = el('button', [el('span', null, 'spinner'), 'Confirming payment…'], 'btn');
    wait.disabled = true;
    actions.push(wait);
  } else if (o.status === 'PENDING_PAYMENT' && o.payment?.checkoutUrl) {
    const pay = el('button', `Pay ${money(o.amount, o.currency)}`, 'btn');
    pay.onclick = () => openCheckout(o);
    actions.push(pay);
  }
  if (!TERMINAL.includes(o.status)) {
    const cancel = el('button', 'Cancel order', 'btn secondary');
    cancel.onclick = () => confirmCancel(o);
    actions.push(cancel);
  }
  if (isAdmin() && o.status === 'COMPLETED' && o.fulfilment && !o.fulfilment.trackingRef) {
    const revoke = el('button', 'Revoke entitlement (admin)', 'btn danger');
    revoke.onclick = () => run(revoke, async () => {
      await api('POST', `/v1/orders/${o.orderId}/revoke-entitlement`);
      toast('Revoke requested — the benefit disappears from Benefits shortly');
    });
    actions.push(revoke);
  }
  if (TERMINAL.includes(o.status)) {
    const benefit = o.status === 'COMPLETED' && o.productType !== 'PHYSICAL_GOOD';
    const primary = el('a', benefit ? 'See it in Benefits' : 'Continue shopping', 'btn');
    primary.href = benefit ? '#/benefits' : '#/store';
    const secondary = el('a', benefit ? 'Continue shopping' : 'Back to my orders', 'btn secondary');
    secondary.href = benefit ? '#/store' : '#/orders';
    actions.push(primary, secondary);
  }
  if (actions.length) parts.push(el('div', actions, 'btn-row'));
  parts.push(el('div', `Order ${o.orderId}`, 'hint mono'));
  return parts;
}

function outcomeBanner(o, mode) {
  const b = (tone, title, text) => el('div', el('div', [el('b', title), text]), `banner ${tone}`);
  switch (o.status) {
    case 'COMPLETED': return b('t-ok', 'All done', `${typeOf(o.productType).deliver}.`);
    case 'CANCELLED': return b('t-bad', 'Order cancelled', 'Nothing was charged.');
    case 'CANCELLED_REFUNDED': return b('t-warn', 'Cancelled and refunded',
      mode === 'PAY_NOW' ? 'Your payment has been refunded.' : 'The charge was removed from your bill.');
    case 'FAILED': return b('t-bad', 'We couldn’t complete this order',
      (o.timeline || []).some((t) => t.status === 'PENDING_PAYMENT') && !o.confirmedAt
        ? 'The payment didn’t go through, so nothing was charged.' : 'Nothing was charged.');
    default: return null;
  }
}

function confirmCancel(o) {
  const yes = el('button', 'Yes, cancel order', 'btn danger');
  const no = el('button', 'Keep order', 'btn secondary');
  no.onclick = closeSheet;
  const problem = el('div');
  yes.onclick = () => run(yes, async () => {
    await api('POST', `/v1/orders/${o.orderId}/cancel`);
    feed.event(`Cancel requested for ${shortId(o.orderId)} — the saga decides at its next checkpoint`);
    closeSheet();
    toast('Cancelling…');
  }, (e) => problem.replaceChildren(e.status === 409
    ? banner('t-warn', 'Too late to cancel', 'This order has already finished.')
    : banner('t-bad', 'Couldn’t cancel', e.message)));
  openSheet(el('div', [
    el('h2', 'Cancel this order?'),
    el('div', o.status === 'PENDING_PAYMENT' || o.status === 'PLACED'
      ? 'You haven’t paid yet, so nothing will be charged.'
      : 'If you’ve already been charged, the payment is refunded automatically.', 'hint'),
    problem,
    el('div', [yes, no], 'btn-row'),
  ]), 'cancel');
}

/** payment-sim's hosted checkout, shown inside the phone. */
function openCheckout(o) {
  const frame = el('iframe', null, 'checkout-frame');
  frame.src = o.payment.checkoutUrl;
  frame.title = 'Secure checkout';
  const external = el('a', 'Open in a new tab', 'text-btn');
  external.href = o.payment.checkoutUrl;
  external.target = '_blank';
  external.rel = 'noopener';
  const lock = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>';
  const secure = el('div', null, 'secure');
  secure.innerHTML = `${lock}<span>Secure checkout by the payment provider · demo OTP 123456</span>`;
  openSheet(el('div', [secure, frame, external], 'checkout'), `checkout:${o.orderId}`, true);
  openCheckoutOrigin = new URL(o.payment.checkoutUrl, location.href).origin;
  feed.event(`Checkout opened on the payment provider for ${shortId(o.orderId)}`);
}

/** payment-sim's result page posts the outcome when it sits in our iframe: close the sheet right away. */
let openCheckoutOrigin = null;
const paidAt = new Set();   // orders the provider authorised that the backend hasn't caught up with yet
window.addEventListener('message', (e) => {
  if (!sheetFor?.startsWith('checkout:') || e.origin !== openCheckoutOrigin || e.data?.source !== 'payment-sim') return;
  const paid = e.data.status === 'AUTHORIZED';
  if (paid) paidAt.add(sheetFor.slice('checkout:'.length));
  feed.event(`Payment provider: ${paid ? 'payment authorised' : 'payment declined'} — webhook on its way to billing`);
  closeCheckoutSoon(paid ? 3500 : 3000,
    paid ? 'Payment authorised — confirming your order…' : 'Payment declined — nothing was charged');
});

/** Leaves the provider's result screen up for a moment, then closes the checkout once (whoever asks first). */
let closingCheckout = null;
function closeCheckoutSoon(ms, message) {
  if (closingCheckout || !sheetFor?.startsWith('checkout:')) return;
  const key = sheetFor;
  closingCheckout = setTimeout(() => {
    closingCheckout = null;
    if (sheetFor !== key) return;   // already closed by hand
    closeSheet();
    toast(message);
    refreshTracker?.();
  }, ms);
}

/** Feeds an event whenever an order we've seen moves to a new status; {@code all}: this is the full list. */
const lastStatus = new Map();
function watchStatuses(orders, all = false) {
  for (const o of orders || []) {
    const prev = lastStatus.get(o.orderId);
    if (prev && prev !== o.status) feed.event(`${shortId(o.orderId)}  ${prev} → ${o.status}`);
    lastStatus.set(o.orderId, o.status);
  }
  const live = [...lastStatus.values()].some((st) => !TERMINAL.includes(st));
  if (all || live) $('ordersDot').hidden = !live;
}

// ── shared pieces ───────────────────────────────────────────────────────────

function signInPrompt(title, text) {
  const btn = el('button', 'Sign in', 'btn');
  btn.onclick = () => oidc.login();
  const box = empty(title, text);
  box.append(btn);
  btn.style.marginTop = '16px';
  return box;
}

function empty(title, text) {
  const box = el('div', [el('h3', title), text ? el('div', text) : null].filter(Boolean), 'empty');
  box.insertAdjacentHTML('afterbegin', '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 7h16l-1.4 11.2a2 2 0 0 1-2 1.8H7.4a2 2 0 0 1-2-1.8z M9 7V6a3 3 0 0 1 6 0v1"/></svg>');
  return box;
}

function kv(pairs) {
  return el('dl', pairs.flatMap(([k, v]) => [el('dt', k), el('dd', v)]), 'kv');
}

function copyable(label, value) {
  const copy = el('button', 'Copy', 'text-btn');
  copy.onclick = async () => {
    try { await navigator.clipboard.writeText(value); toast(`${label} copied`); } catch { toast('Copy failed'); }
  };
  return el('div', [el('div', label, 'hint'), el('div', [el('span', value), copy], 'artifact')]);
}

function demoGuide() {
  const rows = [
    ['Pay', 'OTP 123456 on the checkout page; “Decline” fails the payment'],
    ['Demo: payment provider down', 'payment-sim refuses the payment → order fails, nothing charged'],
    ['Demo: capture hard-decline', 'paid, but settlement is refused → payment voided'],
    ['Demo: delivery failure', 'paid, fulfilment fails → refunded automatically'],
    ['OTTDOWN / OTTBAD plans', 'streaming partner down (cancel to refund) / rejects the plan'],
    ['Demo: billing decline + mobile bill', 'over the credit limit → order fails'],
  ];
  return el('div', [el('b', 'Demo guide'), el('dl', rows.flatMap(([k, v]) => [el('dt', k), el('dd', v)]), 'kv guide')], 'card');
}

function scenarioHint(code) {
  if (code.includes('PSPDOWN')) return 'Scenario: the payment provider is down when the order is placed.';
  if (code.includes('HARDDECLINE')) return 'Scenario: payment succeeds, settlement (capture) is refused.';
  if (code.includes('DELIVERYFAIL')) return 'Scenario: paid, then delivery fails — automatic refund.';
  if (code.includes('DECLINE')) return 'Scenario: with “Add to my mobile bill”, this exceeds the credit limit.';
  if (code.includes('OTTDOWN')) return 'Scenario: the streaming partner is down — the order waits; cancel to get a refund.';
  if (code.includes('OTTBAD')) return 'Scenario: the streaming partner rejects this plan — automatic refund.';
  return 'Demo scenario.';
}

function capitalise(s) { return s ? s[0].toUpperCase() + s.slice(1) : s; }
function initials(c) { return (c?.given_name || c?.preferred_username || '?').slice(0, 1).toUpperCase(); }

// ── sheet + toast ───────────────────────────────────────────────────────────

let sheetFor = null;
function openSheet(content, key, tall = false) {
  sheetFor = key;
  $('sheet').classList.toggle('tall', tall);
  $('sheetBody').replaceChildren(content);
  if (tall) $('sheetBody').style.cssText = 'display:flex;flex-direction:column;gap:12px;flex:1;min-height:0';
  else $('sheetBody').style.cssText = '';
  $('sheet').hidden = false;
  $('sheetBackdrop').hidden = false;
}
function closeSheet() {
  clearTimeout(closingCheckout);
  closingCheckout = null;
  sheetFor = null;
  $('sheet').hidden = true;
  $('sheetBackdrop').hidden = true;
  $('sheetBody').replaceChildren();
}

let toastTimer;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
}

/** Runs an action from a button: disables it meanwhile and shows any error as a toast. */
async function run(button, action, onError) {
  button.disabled = true;
  try {
    await action();
  } catch (e) {
    if (onError) onError(e); else toast(e.message);
  } finally {
    button.disabled = false;
  }
}

// ── under the hood ──────────────────────────────────────────────────────────

const feed = {
  call(method, path, status, ms, req, res, headers) {
    const tone = typeof status !== 'number' || status >= 500 ? 'bad' : status >= 400 ? 'warn' : 'ok';
    const summary = el('summary', [el('span', clock(), 't'), el('span', method, 'm'), el('span', path, 'p'),
      el('span', String(status), `s ${tone}`), el('span', `${ms}ms`, 't')]);
    const shown = { request: req ?? undefined, headers: Object.keys(headers || {}).length ? headers : undefined, response: res };
    this.push(el('details', [summary, el('pre', JSON.stringify(shown, null, 2))]));
  },
  event(text) {
    this.push(el('div', [el('span', clock(), 't'), el('span', '●', 'ev'), el('span', text)], 'event'));
  },
  push(node) {
    const list = $('feed');
    list.prepend(el('li', node));
    while (list.children.length > 80) list.lastChild.remove();
  },
};
function clock() { return new Date().toLocaleTimeString([], { hour12: false }); }

function inspectorVisible() {
  try { return localStorage.getItem('inspector') !== '0'; } catch { return true; }
}
function setInspector(on) {
  try { localStorage.setItem('inspector', on ? '1' : '0'); } catch { /* ignore */ }
  $('inspector').hidden = !on;
}

// ── small helpers ───────────────────────────────────────────────────────────

/** el(tag, child | [children], className?) — pass several children as ONE array; null children are skipped. */
function el(tag, children, className) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  for (const c of [].concat(children ?? [])) {
    if (c === null || c === undefined) continue;
    node.append(c instanceof Node ? c : String(c));
  }
  return node;
}

function renderChrome() {
  const c = oidc.claims();
  $('avatarBtn').textContent = c ? initials(c) : '?';
  $('avatarBtn').onclick = () => { location.hash = '#/profile'; };
}

function tickClock() {
  $('clock').textContent = new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', hour12: false });
}

// ── boot ────────────────────────────────────────────────────────────────────

(async function boot() {
  $('sheetBackdrop').onclick = closeSheet;
  $('sheetClose').onclick = closeSheet;
  $('clearLog').onclick = () => $('feed').replaceChildren();
  $('inspector').hidden = !inspectorVisible();
  tickClock();
  setInterval(tickClock, 15_000);
  feed.event('App loaded — everything below goes through the API gateway');

  try {
    cfg = await (await fetch('/ui/config.json')).json();
    await oidc.completeLoginIfReturning();
  } catch (e) {
    toast(`Sign-in setup failed: ${e.message}`);
  }
  renderChrome();
  window.addEventListener('hashchange', route);
  document.addEventListener('visibilitychange', () => { if (!document.hidden && /#\/orders/.test(location.hash)) route(); });
  route();
})();
