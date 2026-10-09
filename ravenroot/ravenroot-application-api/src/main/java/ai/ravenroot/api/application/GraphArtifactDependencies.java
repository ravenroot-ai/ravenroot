package ai.ravenroot.api.application;

import ai.ravenroot.api.persistence.PinnedNodePackage;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-resolved dependencies for exact admitted GraphML bytes.
 *
 * <p>Resolution is observational: it must not build, activate, admit for execution, or run any
 * artifact. A publication records this evidence and import/deployment resolve it again against the
 * receiving runtime.</p>
 *
 * @param nodePackages installed package identities supplying referenced behaviors
 * @param programs ACTIVE tenant-owned program artifacts selected by exact inline source
 */
public record GraphArtifactDependencies(List<PinnedNodePackage> nodePackages,
                                        List<GraphProgramDependency> programs) {
    /** Contract marker used in CI publication manifests. */
    public static final String CONTRACT = "ravenroot-graph-dependencies-v1";

    /** Takes immutable sorted snapshots and rejects ambiguous duplicate identities. */
    public GraphArtifactDependencies {
        nodePackages = List.copyOf(Objects.requireNonNull(nodePackages, "nodePackages").stream().sorted().toList());
        programs = List.copyOf(Objects.requireNonNull(programs, "programs").stream().sorted().toList());
        if (new HashSet<>(nodePackages.stream().map(PinnedNodePackage::packageId).toList()).size()
                != nodePackages.size()) {
            throw new IllegalArgumentException("node package dependencies must be unique");
        }
        if (new HashSet<>(programs.stream().map(GraphProgramDependency::nodeId).toList()).size()
                != programs.size()) {
            throw new IllegalArgumentException("program dependencies must be unique by node");
        }
    }
}
