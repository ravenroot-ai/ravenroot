package ai.ravenroot.extensions.mail.imap;
import java.util.Optional;
@FunctionalInterface public interface ImapProfileResolver { Optional<ImapProfile> resolve(String tenant, String profile); }
