package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorkspaceServiceTest {
    @TempDir Path temporary;

    @Test
    void provisionsIntegratesAndVerifiesOnlyFreshRemoteHistory() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(1);

        NodeResult provisioned = fixture.invoke(action, fixture.request("provision", null));
        assertEquals("continue", provisioned.outcome());
        assertTrue(Files.isRegularFile(fixture.workspace().resolve(".git")));

        String approved = fixture.commitApproved("accepted\n");
        NodeResult integrated = fixture.invoke(action, fixture.request("integrate", approved));
        assertEquals("continue", integrated.outcome());
        String issueTip = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", GitWorkspaceTestSupport.ISSUE_REF).trim();
        assertEquals(approved, issueTip);

        assertEquals("unmerged", fixture.invoke(action, fixture.request("verify", approved)).outcome());
        NodeResult reprovisioned = fixture.invoke(action, fixture.request("provision", null));
        assertEquals("continue", reprovisioned.outcome());
        assertEquals("provision", ((Map<?, ?>) reprovisioned.payload()).get("operation"));

        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "push", fixture.remote.toUri().toASCIIString(), approved + ":refs/heads/dev");
        assertEquals("continue", fixture.invoke(action, fixture.request("verify", approved)).outcome());

        String acceptedTree = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", approved + "^{tree}").trim();
        String equivalent = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "commit-tree", acceptedTree, "-p", fixture.base,
                "-m", "equivalent").trim();
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "push", fixture.remote.toUri().toASCIIString(), "+" + equivalent + ":refs/heads/dev");
        assertEquals("continue", fixture.invoke(action, fixture.request("verify", approved)).outcome());

        Files.writeString(fixture.source.resolve("later.txt"), "later\n");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "fetch", "origin", "dev");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "reset", "--hard", "FETCH_HEAD");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "add", "later.txt");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "commit", "-m", "later");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "push", "origin", "dev");
        assertEquals("unmerged", fixture.invoke(action, fixture.request("verify", approved)).outcome());
    }

    @Test
    void refusesSiblingAdministrativeDirectorySubstitution() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        Map<String, Object> other = new LinkedHashMap<>(fixture.request("provision", null));
        other.put("taskId", "task-other");
        other.put("issueBranch", "refs/heads/issues/other");
        fixture.invoke(action, Map.copyOf(other));

        GitWorkspaceStore store = new GitWorkspaceStore(fixture.profile(10));
        String originalMarker = Files.readString(store.workspace("task-170").resolve(".git"));
        String siblingMarker = Files.readString(store.workspace("task-other").resolve(".git"));
        Files.writeString(store.workspace("task-170").resolve(".git"), siblingMarker);

        assertEquals("conflict", fixture.invoke(action, fixture.request("provision", null)).outcome());
        Files.writeString(store.workspace("task-170").resolve(".git"), originalMarker);
        Path admin = Path.of(originalMarker.substring("gitdir: ".length()).trim());
        Path reciprocal = admin.resolve("gitdir");
        String originalReciprocal = Files.readString(reciprocal);
        Files.writeString(reciprocal, store.workspace("task-other").resolve(".git").toString());
        assertEquals("conflict", fixture.invoke(action, fixture.request("provision", null)).outcome());
        Files.writeString(reciprocal, originalReciprocal);
        Files.writeString(admin.resolve("commondir"), store.workspace("task-other").toString());
        assertEquals("conflict", fixture.invoke(action, fixture.request("provision", null)).outcome());
    }

    @Test
    void refusesDirtyWorkspaceWithoutMovingIssueRef() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        String approved = fixture.commitApproved("approved\n");
        Files.writeString(fixture.workspace().resolve("dirty.txt"), "unreviewed\n");

        assertEquals("conflict", fixture.invoke(action, fixture.request("integrate", approved)).outcome());
        assertEquals(fixture.base, GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", GitWorkspaceTestSupport.ISSUE_REF).trim());
    }

    @Test
    void acceptsReachableStaleBaseAndRejectsLocallyPresentUnreachableBase() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        Files.writeString(fixture.source.resolve("remote-later.txt"), "later\n");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "add", "remote-later.txt");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "commit", "-m", "later");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "push", "origin", "dev");
        assertEquals("continue", fixture.invoke(fixture.action(10), fixture.request("provision", null)).outcome());

        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "checkout", "--orphan", "unreachable");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "rm", "-rf", ".");
        Files.writeString(fixture.source.resolve("unreachable.txt"), "unreachable\n");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "add", "unreachable.txt");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "commit", "-m", "unreachable");
        String unreachable = GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(),
                "rev-parse", "HEAD").trim();
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "fetch", fixture.source.toUri().toASCIIString(), unreachable + ":refs/ravenroot/unreachable");
        Map<String, Object> request = new LinkedHashMap<>(fixture.request("provision", null));
        request.put("taskId", "unreachable-task");
        request.put("issueBranch", "refs/heads/issues/unreachable");
        request.put("baseRevision", unreachable);
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> fixture.invoke(fixture.action(10), Map.copyOf(request)));
        assertEquals(GitWorkspaceFailure.Code.INVALID_INPUT,
                ((GitWorkspaceFailure) failure.getCause()).code());
    }

    @Test
    void reconcilesRecordedCasAndRefusesChangedTipWithoutMutation() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        String approved = fixture.commitApproved("approved\n");

        GitWorkspaceStore store = new GitWorkspaceStore(fixture.profile(10));
        GitWorkspaceRequest request = GitWorkspaceRequest.parse(fixture.request("integrate", approved),
                fixture.profile(10));
        GitWorkspaceStore.Association ready = store.association("task-170").orElseThrow();
        GitWorkspaceStore.Association pending = ready.begin(request, GitWorkspaceStore.Phase.INTEGRATING,
                fixture.base, approved, GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                        "--git-dir=" + fixture.repository(), "rev-parse", approved + "^{tree}").trim())
                .withTarget(approved);
        store.save(pending);
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "update-ref", GitWorkspaceTestSupport.ISSUE_REF, approved, fixture.base);
        assertEquals("continue", fixture.invoke(action, fixture.request("integrate", approved)).outcome());

        String later = fixture.commitApproved("later\n");
        GitWorkspaceRequest next = GitWorkspaceRequest.parse(fixture.request("integrate", later), fixture.profile(10));
        GitWorkspaceStore.Association state = store.association("task-170").orElseThrow();
        String laterTree = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", later + "^{tree}").trim();
        store.save(state.begin(next, GitWorkspaceStore.Phase.INTEGRATING, approved, later, laterTree)
                .withTarget(later));
        String intruder = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "commit-tree", laterTree, "-p", approved,
                "-m", "intruder").trim();
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "update-ref", GitWorkspaceTestSupport.ISSUE_REF, intruder, approved);

        assertEquals("conflict", fixture.invoke(action, fixture.request("integrate", later)).outcome());
        String observed = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", GitWorkspaceTestSupport.ISSUE_REF).trim();
        assertEquals(intruder, observed);
        assertFalse(observed.equals(later));
    }

    @Test
    void divergentApprovedWorkReportsConflictAndLeavesIssueRefUntouched() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        Files.writeString(fixture.workspace().resolve("base.txt"), "approved side\n");
        GitWorkspaceTestSupport.run(fixture.workspace(), fixture.git.toString(), "config", "user.name", "Reviewer");
        GitWorkspaceTestSupport.run(fixture.workspace(), fixture.git.toString(), "config", "user.email",
                "reviewer@example.invalid");
        GitWorkspaceTestSupport.run(fixture.workspace(), fixture.git.toString(), "add", "base.txt");
        GitWorkspaceTestSupport.run(fixture.workspace(), fixture.git.toString(), "commit", "-m", "approved side");
        String approved = GitWorkspaceTestSupport.run(fixture.workspace(), fixture.git.toString(),
                "rev-parse", "HEAD").trim();

        Files.writeString(fixture.source.resolve("base.txt"), "issue side\n");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "add", "base.txt");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "commit", "-m", "issue side");
        String alternate = GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(),
                "rev-parse", "HEAD").trim();
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "fetch", fixture.source.toUri().toASCIIString(), alternate + ":refs/ravenroot/alternate");
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "update-ref", GitWorkspaceTestSupport.ISSUE_REF, alternate, fixture.base);

        assertEquals("conflict", fixture.invoke(action, fixture.request("integrate", approved)).outcome());
        assertEquals(alternate, GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", GitWorkspaceTestSupport.ISSUE_REF).trim());
    }

    @Test
    void cleanDivergenceProducesOneDeterministicTwoParentMerge() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        fixture.invoke(action, fixture.request("provision", null));
        String approved = fixture.commitApproved("approved\n");

        Files.writeString(fixture.source.resolve("issue.txt"), "issue\n");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "add", "issue.txt");
        GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "commit", "-m", "issue side");
        String issue = GitWorkspaceTestSupport.run(fixture.source, fixture.git.toString(), "rev-parse", "HEAD").trim();
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "fetch", fixture.source.toUri().toASCIIString(), issue + ":refs/ravenroot/issue-side");
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "update-ref", GitWorkspaceTestSupport.ISSUE_REF, issue, fixture.base);

        NodeResult merged = fixture.invoke(action, fixture.request("integrate", approved));
        assertEquals("continue", merged.outcome());
        String target = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", GitWorkspaceTestSupport.ISSUE_REF).trim();
        assertEquals(issue + " " + approved, GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "show", "-s", "--format=%P", target).trim());
        assertEquals("approved\n", GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "show", target + ":approved.txt"));
        assertEquals("issue\n", GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "show", target + ":issue.txt"));
        assertEquals("continue", fixture.invoke(action, fixture.request("integrate", approved)).outcome());
        assertEquals(target, GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", GitWorkspaceTestSupport.ISSUE_REF).trim());
    }

    @Test
    void concurrentValidProvisionRequestsConvergeOnOneWorkspace() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = java.util.stream.IntStream.range(0, 4)
                    .<java.util.concurrent.Callable<NodeResult>>mapToObj(ignored ->
                            () -> fixture.invoke(action, fixture.request("provision", null))).toList();
            for (var result : executor.invokeAll(calls)) assertEquals("continue", result.get().outcome());
        }
        assertTrue(Files.isRegularFile(fixture.workspace().resolve(".git")));
        try (var records = Files.list(new GitWorkspaceStore(fixture.profile(10))
                .home().getParent().resolve("associations"))) {
            assertEquals(1, records.filter(path -> path.getFileName().toString().endsWith(".json")).count());
        }
    }

    @Test
    void restartsAcrossProvisionIntegrationAndVerificationStateBoundaries() throws Exception {
        GitWorkspaceTestSupport fixture = new GitWorkspaceTestSupport(temporary);
        NodeAction action = fixture.action(10);
        GitWorkspaceStore store = new GitWorkspaceStore(fixture.profile(10));
        GitWorkspaceRequest provision = GitWorkspaceRequest.parse(fixture.request("provision", null),
                fixture.profile(10));
        store.reserve(GitWorkspaceStore.Association.initial(provision)
                .begin(provision, GitWorkspaceStore.Phase.PROVISIONING, "0".repeat(40), "", "")
                .withTarget(fixture.base));
        assertEquals("continue", fixture.invoke(action, fixture.request("provision", null)).outcome());

        String approved = fixture.commitApproved("approved\n");
        GitWorkspaceRequest integrate = GitWorkspaceRequest.parse(fixture.request("integrate", approved),
                fixture.profile(10));
        String tree = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", approved + "^{tree}").trim();
        store.save(store.association("task-170").orElseThrow().begin(integrate,
                GitWorkspaceStore.Phase.INTEGRATING, fixture.base, approved, tree));
        assertEquals("continue", fixture.invoke(action, fixture.request("integrate", approved)).outcome());

        GitWorkspaceRequest verify = GitWorkspaceRequest.parse(fixture.request("verify", approved),
                fixture.profile(10));
        store.save(store.association("task-170").orElseThrow().begin(verify,
                GitWorkspaceStore.Phase.VERIFYING, approved, approved, tree));
        assertEquals("unmerged", fixture.invoke(action, fixture.request("verify", approved)).outcome());
    }
}
