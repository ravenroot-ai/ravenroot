package ai.ravenroot.extensions.mail.imap;

import java.util.Set;

public record ImapProfile(String tenant, String id, String host, int port, String securityMode,
                          String username, String credentialRef, Set<String> folders,
                          int connectTimeoutMs, int readTimeoutMs, int maxConcurrency,
                          int maxResults, int maxPreviewChars) {
    /**
     * {@code folders} is a set of names, and "empty" used to be tested on that set before any
     * name in it was normalised. {@code "".split(",")} yields one element -- the empty string, not
     * zero elements -- so a {@code folders} field that was entirely blank built a set of size one,
     * {@code {""}}, which is not empty by that test. The profile resolved, and every query against it
     * then failed with {@code INVALID_INPUT}: no request can ever name the blank folder, because a
     * blank requested folder is itself rejected upstream in {@code MailImapQueryNodeBehavior}. A
     * single stray comma in an otherwise-valid list ({@code ",INBOX"}, {@code "INBOX,,Archive"}) hit
     * the same defect without breaking anything -- it silently carried a useless blank member
     * alongside folder names that still worked.
     *
     * <p>The fix strips every name and discards the ones that go blank <em>before</em> testing
     * emptiness, so a field that is blank throughout -- or reduces to nothing but blanks after a
     * stray comma -- is indistinguishable from supplying no folders at all, and is refused the same
     * way. A stray comma among otherwise-valid names now simply drops the phantom entry instead of
     * carrying it forward: the profile keeps resolving and keeps working for every real folder it
     * names, which is the compatible half of this fix -- no profile that queries a real folder today
     * stops resolving because of it.
     */
    public ImapProfile {
        if (tenant == null || tenant.isBlank() || id == null || id.isBlank() || host == null || host.isBlank()
                || !Set.of("IMAPS", "STARTTLS").contains(securityMode) || port < 1 || port > 65535
                || username == null || username.isBlank() || credentialRef == null || credentialRef.isBlank()
                || folders == null || connectTimeoutMs < 1 || readTimeoutMs < 1
                || maxConcurrency < 1 || maxConcurrency > 16 || maxResults < 1 || maxResults > 500
                || maxPreviewChars < 0 || maxPreviewChars > 65536) throw new IllegalArgumentException("Invalid IMAP profile");
        folders = folders.stream().map(String::strip).filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (folders.isEmpty()) throw new IllegalArgumentException("Invalid IMAP profile");
    }
}
