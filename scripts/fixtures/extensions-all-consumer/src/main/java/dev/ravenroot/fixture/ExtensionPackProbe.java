package dev.ravenroot.fixture;

import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.extensions.ai.AiNodePackage;
import ai.ravenroot.extensions.amqp091.AmqpNodePackage;
import ai.ravenroot.extensions.filesystem.FilesystemNodePackage;
import ai.ravenroot.extensions.github.GithubNodePackage;
import ai.ravenroot.extensions.gitworkspace.GitWorkspaceNodePackage;
import ai.ravenroot.extensions.jdbc.JdbcNodePackage;
import ai.ravenroot.extensions.kafka.KafkaNodePackage;
import ai.ravenroot.extensions.mail.MailNodePackage;
import ai.ravenroot.extensions.ocr.OcrNodePackage;
import ai.ravenroot.extensions.openapi.client.OpenApiClientNodePackage;
import ai.ravenroot.extensions.openapi.server.OpenApiServerNodePackage;
import ai.ravenroot.extensions.spel.SpelNodePackage;
import ai.ravenroot.extensions.storage.StorageNodePackage;
import ai.ravenroot.extensions.telegram.TelegramNodePackage;
import ai.ravenroot.extensions.websocket.WebSocketNodePackage;

import java.util.List;
import java.util.ServiceLoader;

/** Compile-time and runtime probe for the staged dependency pack. */
public final class ExtensionPackProbe {
    private static final List<Class<? extends NodePackage>> PACKAGE_TYPES = List.of(
            AiNodePackage.class,
            AmqpNodePackage.class,
            FilesystemNodePackage.class,
            GitWorkspaceNodePackage.class,
            GithubNodePackage.class,
            JdbcNodePackage.class,
            KafkaNodePackage.class,
            MailNodePackage.class,
            StorageNodePackage.class,
            OcrNodePackage.class,
            OpenApiClientNodePackage.class,
            OpenApiServerNodePackage.class,
            SpelNodePackage.class,
            TelegramNodePackage.class,
            WebSocketNodePackage.class);

    private ExtensionPackProbe() {
    }

    public static void main(String[] arguments) {
        if (PACKAGE_TYPES.size() != 15) {
            throw new IllegalStateException("the complete first-party extension set was not compiled");
        }
        if (ServiceLoader.load(NodePackage.class).stream().findAny().isPresent()) {
            throw new IllegalStateException("classpath presence must not discover a NodePackage");
        }
    }
}
