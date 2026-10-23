package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import java.util.Optional;
import java.util.Set;

/** Test composition only: the actual sender with explicit operator-owned loopback authority. */
public final class MixedExampleMail {
    private MixedExampleMail() {}
    public static NodeBehavior sender(int port) {
        var profile = new MailProfile("example", "mixed-mail", "127.0.0.1", port, "SMTP", true,
                "", "", "from@example.test", Set.of("from@example.test"), Set.of(), Set.of("*"), Set.of(),
                10, 4, 1024, 4096, 0, 1024, 1024, 2048, 10000, 10000, 10000, 0, 4);
        return new MailSendNodeBehavior(reference -> { throw new AssertionError("anonymous fixture has no credential"); },
                (tenant, name) -> tenant.equals("example") && name.equals("mixed-mail") ? Optional.of(profile) : Optional.empty(),
                SecretValue::copy, String::new,
                ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
    }
}
