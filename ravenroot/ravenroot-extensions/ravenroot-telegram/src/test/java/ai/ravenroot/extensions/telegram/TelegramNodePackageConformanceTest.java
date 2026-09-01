package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class TelegramNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() {
        return new NodePackage() {
            @Override public String id() { return "ai.ravenroot.extensions.telegram"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return ai.ravenroot.api.node.NodeSdk.CONTRACT; }
            @Override public List<ai.ravenroot.api.node.NodeBehavior> behaviors() {
                var credentials = (ai.ravenroot.api.security.CredentialResolver) reference -> Optional.empty();
                TelegramProfileResolver profiles = (tenant, name) -> Optional.of(
                        TelegramTestSupport.profile(tenant, "missing", 0));
                return List.of(new TelegramSendNodeBehavior(credentials, profiles),
                        new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK,
                                credentials, profiles),
                        new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE,
                                credentials, profiles),
                        new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE,
                                credentials, profiles));
            }
        };
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("telegram", descriptor.behavior(), Map.of("botProfile", TelegramTestSupport.PROFILE));
    }
}
