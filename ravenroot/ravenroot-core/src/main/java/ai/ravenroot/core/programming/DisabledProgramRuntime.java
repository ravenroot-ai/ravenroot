package ai.ravenroot.core.programming;

import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Safe default: installing Ravenroot never enables arbitrary code execution. */
public final class DisabledProgramRuntime implements ProgramRuntime {
    @Override
    public String id() {
        return "disabled";
    }

    @Override
    public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Programmable-node execution is disabled; install and configure an isolated runtime adapter"));
    }
}
