package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/** Source-installable Telegram Bot API node package. */
public final class TelegramNodePackage implements NodePackage {
    @Override public String id() { return "ai.ravenroot.extensions.telegram"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() {
        return List.of(
                new TelegramSendNodeBehavior(),
                new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK),
                new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE),
                new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE));
    }
}
