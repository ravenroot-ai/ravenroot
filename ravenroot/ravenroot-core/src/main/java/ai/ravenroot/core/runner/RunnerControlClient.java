package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Worker-only transport boundary; never accepts an identity from an assignment or caller. */
public interface RunnerControlClient {
    void register(RunnerRegistration registration) throws IOException, InterruptedException;
    void availability(int capacity, int active, Set<String> profiles, Duration ttl) throws IOException, InterruptedException;
    Map<String, Object> assignments(String cursor) throws IOException, InterruptedException;
    RunnerAssignment assignment(UUID process, UUID job) throws IOException, InterruptedException;
    void workspaceStopped(RunnerAssignment assignment) throws IOException, InterruptedException;
    RunnerWorkspaceRelease release(UUID process, String node) throws IOException, InterruptedException;
    void workspaceReleased(UUID process, String node, RunnerWorkspaceRelease release) throws IOException, InterruptedException;
    RunnerAssignment claim(UUID process, UUID job, boolean reconcile) throws IOException, InterruptedException;
    RunnerAssignment heartbeat(RunnerAssignment assignment) throws IOException, InterruptedException;
    RunnerAssignment complete(RunnerAssignment assignment, RunnerResult result) throws IOException, InterruptedException;
    RunnerArtifact upload(RunnerAssignment assignment, RunnerArtifact.Kind kind, byte[] bytes) throws IOException, InterruptedException;
}
