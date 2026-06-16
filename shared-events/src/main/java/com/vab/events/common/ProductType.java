package com.vab.events.common;

/**
 * The product type of an offer — the single shared vocabulary that catalog,
 * order, inventory and fulfilment all agree on (supersedes the per-service
 * taxonomies: catalog's free {@code category} string and inventory's local
 * {@code InventoryType} enum).
 *
 * <p>Type drives three things now, not just reservation:
 * <ul>
 *   <li><b>inventory</b> — {@code PHYSICAL_GOOD} / {@code SOFTWARE_LICENSE} are
 *       <em>finite</em> and have an inventory row; {@code DIGITAL_SUBSCRIPTION}
 *       is <em>infinite</em> and has no row (the saga skips the reserve step).</li>
 *   <li><b>fulfilment</b> — physical → create a shipment (tracking ref);
 *       digital → provision an OTT entitlement (external ref); license →
 *       allocate an activation key.</li>
 *   <li><b>display / notification</b> — the read model and templates render the
 *       right artifact per type.</li>
 * </ul>
 *
 * <p>The {@code SLOT} type and its service-centre concept were removed in the
 * product-types redesign (see Design/09).
 */
public enum ProductType {
    PHYSICAL_GOOD,
    DIGITAL_SUBSCRIPTION,
    SOFTWARE_LICENSE
}
