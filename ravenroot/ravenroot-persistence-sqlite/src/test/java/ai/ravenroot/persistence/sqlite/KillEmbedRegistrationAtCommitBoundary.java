package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedRevokeCommand;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

/**
 * The program {@link SqliteEmbedRegistrationKillAtCommitBoundaryTest} forks and then {@code SIGKILL}s.
 *
 * <p>It revokes a registration that the parent has already provisioned, parks at the requested commit
 * boundary and says so on stdout. It never exits on its own, so a run that prints {@link #COMPLETED}
 * means the kill did not land and the test is invalid rather than passing — the same discipline
 * {@link KillAtCommitBoundary} uses, and for the same reason.</p>
 *
 * <p>A revocation is the operation chosen for this test rather than a provision. A provision lost to
 * a crash leaves an embed that does not work, which an operator will notice; a revocation lost to a
 * crash leaves an embed that <em>does</em> work after someone decided it should not, which nobody
 * will.</p>
 */
public final class KillEmbedRegistrationAtCommitBoundary {

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";
    static final String BEFORE_COMMIT = "before";
    static final String AFTER_COMMIT = "after";

    private KillEmbedRegistrationAtCommitBoundary() {
    }

    public static void main(String[] args) {
        Path directory = Path.of(args[0]);
        String mode = args[1];
        long expectedRevision = Long.parseLong(args[2]);

        CommitBoundary boundary = AFTER_COMMIT.equals(mode)
                ? new CommitBoundary() {
                    @Override
                    public void afterCommit() {
                        parkForever();
                    }
                }
                : new CommitBoundary() {
                    @Override
                    public void beforeCommit() {
                        parkForever();
                    }
                };

        var store = SqliteEmbedRegistrationStore.openUnder(directory,
                Clock.fixed(EmbedRegistrationFixtures.AT, ZoneOffset.UTC),
                EmbedProjectionBudget.DEFAULTS, boundary);
        store.revoke(new EmbedRevokeCommand(EmbedRegistrationFixtures.REGISTRATION,
                EmbedRegistrationFixtures.TENANT, expectedRevision));
        System.out.println(COMPLETED);
        System.out.flush();
    }

    private static void parkForever() {
        System.out.println(AT_BOUNDARY);
        System.out.flush();
        try {
            Thread.sleep(Duration.ofMinutes(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
