package ai.ravenroot.api.embed;

import java.util.Objects;

/** Versioned and mutually-exclusive source selected by an embed registration. */
public sealed interface EmbedViewerSource permits EmbedViewerSource.Snapshot, EmbedViewerSource.Deployment,
        EmbedViewerSource.DeploymentV2 {
    /** Current version of the mutually-exclusive viewer source contract. */
    String CURRENT_VERSION = "1";

    /** Returns the version of this viewer source contract.
     * @return version of this viewer source contract
     */
    String viewerSourceVersion();

    /**
     * Existing immutable captured snapshot behaviour.
     * @param viewerSourceVersion version of the mutually-exclusive viewer source contract
     */
    record Snapshot(String viewerSourceVersion) implements EmbedViewerSource {
        /** Validates the source-contract version. */
        public Snapshot {
            if (!CURRENT_VERSION.equals(viewerSourceVersion)) {
                throw new IllegalArgumentException("unsupported viewer source version");
            }
        }

        /** Creates a snapshot source using {@link #CURRENT_VERSION}. */
        public Snapshot() { this(CURRENT_VERSION); }
    }

    /**
     * Tenant-scoped live deployment, resolved server-side when a viewer session binds.
     * @param viewerSourceVersion version of the mutually-exclusive viewer source contract
     * @param deploymentId tenant-scoped deployment identifier resolved by the server
     */
    record Deployment(String viewerSourceVersion, String deploymentId) implements EmbedViewerSource {
        /** Validates the source-contract version and deployment identity. */
        public Deployment {
            if (!CURRENT_VERSION.equals(viewerSourceVersion)) {
                throw new IllegalArgumentException("unsupported viewer source version");
            }
            Objects.requireNonNull(deploymentId, "deploymentId");
            if (deploymentId.isBlank()) throw new IllegalArgumentException("deploymentId must not be blank");
        }

        /**
         * Creates a deployment source using {@link #CURRENT_VERSION}.
         * @param deploymentId tenant-scoped deployment identifier
         */
        public Deployment(String deploymentId) { this(CURRENT_VERSION, deploymentId); }
    }

    /**
     * Additive live-deployment source with selectable runs and presentation-only execution affordance.
     * The boolean never grants authority; {@link EmbedCapability#DEPLOYMENT_EXECUTE} is checked
     * independently at every server action.
     * @param viewerSourceVersion fixed version {@code 2}
     * @param deploymentId tenant-scoped deployment selected by the operator
     * @param showStartExecution whether the viewer may present the start affordance
     */
    record DeploymentV2(String viewerSourceVersion, String deploymentId,
                        boolean showStartExecution) implements EmbedViewerSource {
        /** Validates the versioned deployment source. */
        public DeploymentV2 {
            if (!"2".equals(viewerSourceVersion)) {
                throw new IllegalArgumentException("unsupported viewer source version");
            }
            Objects.requireNonNull(deploymentId, "deploymentId");
            if (deploymentId.isBlank()) throw new IllegalArgumentException("deploymentId must not be blank");
        }

        /**
         * Creates a version-two deployment source.
         * @param deploymentId tenant-scoped deployment identifier
         * @param showStartExecution whether the viewer may present the start affordance
         */
        public DeploymentV2(String deploymentId, boolean showStartExecution) {
            this("2", deploymentId, showStartExecution);
        }
    }

    /** Returns an immutable captured-snapshot source.
     * @return immutable captured-snapshot source
     */
    static EmbedViewerSource snapshot() { return new Snapshot(); }
    /**
     * Creates a live-deployment viewer source.
     * @param deploymentId tenant-scoped deployment identifier
     * @return deployment viewer source
     */
    static EmbedViewerSource deployment(String deploymentId) { return new Deployment(deploymentId); }

    /**
     * Creates an additive v2 deployment viewer source.
     * @param deploymentId tenant-scoped deployment identifier
     * @param showStartExecution whether the viewer may present the start affordance
     * @return version-two deployment viewer source
     */
    static EmbedViewerSource deploymentV2(String deploymentId, boolean showStartExecution) {
        return new DeploymentV2(deploymentId, showStartExecution);
    }
}
