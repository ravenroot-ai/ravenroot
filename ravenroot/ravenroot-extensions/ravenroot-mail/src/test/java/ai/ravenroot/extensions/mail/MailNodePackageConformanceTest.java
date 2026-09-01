package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() {
        return new NodePackage() {
            @Override public String id() { return "ai.ravenroot.extensions.mail"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return ai.ravenroot.api.node.NodeSdk.CONTRACT; }
            @Override public java.util.List<ai.ravenroot.api.node.NodeBehavior> behaviors() {
                return java.util.List.of(new MailSendNodeBehavior(ref -> java.util.Optional.empty(),
                        (tenant, name) -> java.util.Optional.of(MailTestSupport.profile(tenant, name,
                                "127.0.0.1", 2525, "SMTP", "", "", 0))));
            }
        };
    }

    @Test void exposesTheInspectorSchemaWithoutAUiContractChange() {
        var descriptor = new MailSendNodeBehavior().descriptor();
        assertEquals("mail.send", descriptor.behavior());
        var types = descriptor.properties().stream().collect(java.util.stream.Collectors.toMap(p -> p.name(), p -> p.type()));
        var expected = java.util.Map.ofEntries(
                java.util.Map.entry("mailProfile", NodePropertyType.STRING), java.util.Map.entry("host", NodePropertyType.STRING),
                java.util.Map.entry("port", NodePropertyType.INTEGER), java.util.Map.entry("securityMode", NodePropertyType.STRING),
                java.util.Map.entry("tlsVerify", NodePropertyType.BOOLEAN), java.util.Map.entry("authUsername", NodePropertyType.STRING),
                java.util.Map.entry("credentialRef", NodePropertyType.SECRET_REFERENCE), java.util.Map.entry("connectTimeoutMs", NodePropertyType.INTEGER),
                java.util.Map.entry("readTimeoutMs", NodePropertyType.INTEGER), java.util.Map.entry("writeTimeoutMs", NodePropertyType.INTEGER),
                java.util.Map.entry("retries", NodePropertyType.INTEGER), java.util.Map.entry("maxRecipients", NodePropertyType.INTEGER),
                java.util.Map.entry("maxHeaders", NodePropertyType.INTEGER), java.util.Map.entry("maxHeaderChars", NodePropertyType.INTEGER),
                java.util.Map.entry("maxBodyChars", NodePropertyType.INTEGER), java.util.Map.entry("maxAttachments", NodePropertyType.INTEGER),
                java.util.Map.entry("maxAttachmentBytes", NodePropertyType.INTEGER), java.util.Map.entry("maxTotalAttachmentBytes", NodePropertyType.INTEGER),
                java.util.Map.entry("maxEncodedAttachmentBytes", NodePropertyType.INTEGER), java.util.Map.entry("maxConcurrency", NodePropertyType.INTEGER),
                java.util.Map.entry("defaultFrom", NodePropertyType.STRING));
        assertEquals(expected, types);
        assertTrue(descriptor.properties().stream().filter(property -> property.name().equals("maxConcurrency"))
                .findFirst().orElseThrow().description().contains("1–16"));
        assertTrue(descriptor.capabilities().contains("network"));
    }

    @Test void discoversTheImapBehaviorsAlongsideMailSend() {
        var behaviors = new MailNodePackage().behaviors();
        assertEquals(java.util.Set.of("mail.send", "mail.imap.query", "mail.imap.consume", "mail.imap.move",
                        "mail.imap.delete"),
                behaviors.stream().map(behavior -> behavior.descriptor().behavior())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        var base = super.configurationFor(descriptor);
        var properties = new java.util.LinkedHashMap<>(base.properties());
        properties.put("mailProfile", MailTestSupport.PROFILE);
        properties.put("credentialRef", "");
        return new NodeConfiguration(base.nodeId(), base.behavior(), properties);
    }
}
