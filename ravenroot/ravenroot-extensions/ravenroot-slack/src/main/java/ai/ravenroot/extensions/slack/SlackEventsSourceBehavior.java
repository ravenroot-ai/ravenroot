package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;

/** Managed Slack Events API callback source. */
public final class SlackEventsSourceBehavior extends SlackIngressSourceBehavior {
    SlackEventsSourceBehavior(SlackRuntime runtime) { super(runtime, Kind.EVENTS); }
    @Override public NodeTypeDescriptor descriptor() { return SlackBehaviorDescriptors.events(); }
}
