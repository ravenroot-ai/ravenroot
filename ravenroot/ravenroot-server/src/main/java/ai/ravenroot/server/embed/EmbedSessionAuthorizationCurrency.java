package ai.ravenroot.server.embed;

/** Current-authority check shared by launch tickets, exchanges, bearers and streams. */
@FunctionalInterface
public interface EmbedSessionAuthorizationCurrency {
    boolean isCurrent(EmbedSessionAuthorization authorization);
}
