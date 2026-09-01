package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/** Operator-loadable SMTP and IMAP node package. */
public final class MailNodePackage implements NodePackage {
    @Override public String id() { return "ai.ravenroot.extensions.mail"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() {
        return List.of(new MailSendNodeBehavior(),
                new ai.ravenroot.extensions.mail.imap.MailImapQueryNodeBehavior(),
                new ai.ravenroot.extensions.mail.imap.MailImapConsumeNodeBehavior(),
                new ai.ravenroot.extensions.mail.imap.MailImapMutationNodeBehavior(
                        ai.ravenroot.extensions.mail.imap.MailImapMutationNodeBehavior.Kind.MOVE),
                new ai.ravenroot.extensions.mail.imap.MailImapMutationNodeBehavior(
                        ai.ravenroot.extensions.mail.imap.MailImapMutationNodeBehavior.Kind.DELETE));
    }
}
