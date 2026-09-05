/*
 * Copyright (c) 2025-2026 Alessio Ghironi
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.ravenroot.extensions.all;

/**
 * Marker for the first-party extension dependency pack.
 *
 * <p>This type intentionally has no behavior. In particular, it does not discover, register, or
 * activate node packages; applications retain those responsibilities at their composition boundary.
 */
public final class ExtensionPack {

    private ExtensionPack() {
    }
}
