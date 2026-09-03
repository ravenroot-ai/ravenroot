package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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

        String acceptedTree = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "rev-parse", approved + "^{tree}").trim();
        String equivalent = GitWorkspaceTestSupport.run(temporary, fixture.git.toString(),
                "--git-dir=" + fixture.repository(), "commit-tree", acceptedTree, "-p", fixture.base,
                "-m", "equivalent").trim();
        GitWorkspaceTestSupport.run(temporary, fixture.git.toString(), "--git-dir=" + fixture.repository(),
                "push", fixture.remote.toUri().toASCIIString(), equivalent + ":refs/heads/dev");
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
}
