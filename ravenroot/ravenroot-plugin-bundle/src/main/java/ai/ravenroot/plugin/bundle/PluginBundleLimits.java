package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.payload.PayloadLimits;

/**
 * Resource budgets for a plugin bundle manifest (PLAT-12).
 *
 * <h2>Why not {@link PayloadLimits#DEFAULTS}</h2>
 * <p>{@link PayloadLimits} is reused because a manifest and an API payload share the same hazard
 * shape — encoded size, nesting depth, collection size, total value count, text length, key length
 * are exactly the axes a hostile document of either kind would try to abuse. {@code DEFAULTS}
 * itself is not reused: it is sized for an arbitrary API call argument, a domain whose legitimate
 * traffic is far larger and more varied than a manifest's. A real manifest is a few kilobytes with a
 * handful of declared artifacts; a manifest anywhere near {@code DEFAULTS}'s 1,000-element collection
 * ceiling would itself be the anomaly these bundle-count and size limits exist to catch. Reusing
 * the type but not the constant keeps the budget honest for what this document actually is.</p>
 *
 * <h2>How the numbers were chosen</h2>
 * <ul>
 *   <li>{@code maxEncodedBytes} (64 KiB) — generous for a document that is realistically a few KB
 *       even with several dozen declared dependency artifacts and full metadata.</li>
 *   <li>{@code maxDepth} (8) — the deepest legitimate shape is manifest → artifacts list → one
 *       artifact object → its fields, i.e. depth 4; double that for headroom without leaving the
 *       budget effectively unbounded.</li>
 *   <li>{@code maxCollectionSize} (256) — an order of magnitude above any plausible dependency count
 *       for a single bundle. A manifest declaring close to this many runtime dependencies is a
 *       packaging defect worth surfacing on its own, not a case to size for.</li>
 *   <li>{@code maxValueCount} (4,096) — bounds the whole tree even if every collection were near its
 *       own ceiling at once.</li>
 *   <li>{@code maxTextLength} (4 KiB) — covers a description field generously without admitting an
 *       embedded document.</li>
 *   <li>{@code maxKeyLength} (128) — every manifest key is a short, author-chosen identifier.</li>
 * </ul>
 */
final class PluginBundleLimits {

    /** The budgets applied to every plugin bundle manifest. */
    static final PayloadLimits MANIFEST = new PayloadLimits(
            64 * 1024,
            8,
            256,
            4_096,
            4 * 1024,
            128);

    private PluginBundleLimits() {
    }
}
