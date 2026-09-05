package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;

/** Managed Slack slash-command callback source. */
public final class SlackCommandsSourceBehavior extends SlackIngressSourceBehavior {
    SlackCommandsSourceBehavior(SlackRuntime runtime) { super(runtime, Kind.COMMANDS); }
    @Override public NodeTypeDescriptor descriptor() { return SlackBehaviorDescriptors.commands(); }
}
