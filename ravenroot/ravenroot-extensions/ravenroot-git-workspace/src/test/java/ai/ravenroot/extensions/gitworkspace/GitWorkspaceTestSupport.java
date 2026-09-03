package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class GitWorkspaceTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "workspace";
    static final String ISSUE_REF = "refs/heads/issues/170";
    final Path root;
    final Path remote;
    final Path source;
    final Path git;
    final String base;

    GitWorkspaceTestSupport(Path temporary) throws Exception {
        root = Files.createDirectory(temporary.resolve("authority"));
        remote = temporary.resolve("remote.git");
        source = Files.createDirectory(temporary.resolve("source"));
        git = Path.of(run(temporary, "sh", "-c", "command -v git").trim()).toAbsolutePath().normalize();
        run(temporary, git.toString(), "init", "--bare", "--initial-branch=dev", remote.toString());
        run(source, git.toString(), "init", "--initial-branch=dev");
        run(source, git.toString(), "config", "user.name", "Test");
        run(source, git.toString(), "config", "user.email", "test@example.invalid");
        Files.writeString(source.resolve("base.txt"), "base\n");
        run(source, git.toString(), "add", "base.txt");
        run(source, git.toString(), "commit", "-m", "base");
        base = run(source, git.toString(), "rev-parse", "HEAD").trim();
        run(source, git.toString(), "remote", "add", "origin", remote.toUri().toASCIIString());
        run(source, git.toString(), "push", "origin", "dev");
    }

    GitWorkspaceProfile profile(int historyLimit) {
        return new GitWorkspaceProfile(TENANT, PROFILE, root, remote.toUri().toASCIIString(),
                "refs/heads/dev", "refs/heads/issues/", git, "sha1", null, null,
                Duration.ofSeconds(30), 4, 256 * 1024, historyLimit);
    }

    NodeAction action(int historyLimit) {
        GitWorkspaceProfile profile = profile(historyLimit);
        return new GitWorkspaceNodeBehavior((tenant, name) -> Optional.of(profile))
                .create(new NodeConfiguration("git", GitWorkspaceNodeBehavior.BEHAVIOR,
                        Map.of("workspaceProfile", PROFILE)));
    }

    NodeResult invoke(NodeAction action, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        NodeMessage message = new NodeMessage(new SecurityContext("request", TENANT, "subject",
                PrincipalType.WORKLOAD, "issuer"), id, id, id, id, Set.of(), "git", payload, Map.of());
        return action.handle(message).toCompletableFuture().join();
    }

    Map<String, Object> request(String operation, String revision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contract", GitWorkspaceRequest.CONTRACT);
        value.put("operation", operation);
        value.put("taskId", "task-170");
        value.put("baseRevision", base);
        value.put("issueBranch", ISSUE_REF);
        if ("integrate".equals(operation)) value.put("approvedRevision", revision);
        if ("verify".equals(operation)) value.put("acceptedRevision", revision);
        return Map.copyOf(value);
    }

    Path workspace() {
        return new GitWorkspaceStore(profile(1)).workspace("task-170");
    }

    Path repository() {
        return new GitWorkspaceStore(profile(1)).repository();
    }

    String commitApproved(String content) throws Exception {
        Files.writeString(workspace().resolve("approved.txt"), content);
        run(workspace(), git.toString(), "config", "user.name", "Reviewer");
        run(workspace(), git.toString(), "config", "user.email", "reviewer@example.invalid");
        run(workspace(), git.toString(), "add", "approved.txt");
        run(workspace(), git.toString(), "commit", "-m", "approved");
        return run(workspace(), git.toString(), "rev-parse", "HEAD").trim();
    }

    static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(String.join(" ", command) + " failed: " + output);
        return output;
    }
}
