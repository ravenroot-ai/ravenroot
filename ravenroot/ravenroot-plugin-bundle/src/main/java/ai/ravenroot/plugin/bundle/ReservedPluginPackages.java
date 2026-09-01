package ai.ravenroot.plugin.bundle;

import java.util.List;
import java.util.Locale;

/**
 * Java packages a plugin bundle's own classes may never declare themselves in, and a second, related
 * but distinct set this class also exposes: packages a bundle's dedicated classloader always prefers
 * the host's own version of, at runtime (PLAT-12).
 *
 * <h2>The principle: reserve/prefer what the host provides, not what the JVM happens to own</h2>
 * <p>Every prefix on either list below exists because something else -- the JVM's own bootstrap
 * classloader, or {@code ravenroot-core}/{@code ravenroot-application-api} -- already provides a real,
 * trust-sensitive implementation under it, and a bundle must never be able to shadow or spoof that
 * implementation. This is <em>not</em> the same question as "does the JVM itself refuse to define a
 * class there" -- that framing is too narrow in the wrong direction: the JVM has no opinion whatsoever
 * about {@code ai.ravenroot.api.}/{@code core.}/{@code server.}/{@code cli.}/{@code distribution.}, yet
 * all five belong on {@link #isReserved the validator's set} precisely because the <em>host</em>
 * provides real implementations there. Apply this test to any candidate prefix before adding it to
 * either list: "does the host (JVM or ravenroot-core/application-api) provide a real implementation
 * under this prefix that a bundle must not be able to shadow?" -- not "does the JVM already forbid
 * user code from defining a class here?" The two questions happen to agree for {@code java.}/
 * {@code sun.}/{@code jdk.}/{@code com.sun.}, which is exactly what made the narrower, JVM-ownership
 * framing look sufficient the first time this was written -- until it produced a wrong answer for
 * {@code javax.} (see below).
 *
 * <h2>Why not all of {@code ai.ravenroot.*}</h2>
 * <p>The dogfooding {@code ravenroot-mail} bundle already ships at
 * {@code ai.ravenroot.extensions.mail}. Reserving the whole {@code ai.ravenroot.} root would make
 * the repository's own first-party bundle fail the check this class exists to enforce, which is
 * exactly the kind of special case that makes a reserved-namespace rule rot. Reserving precisely the
 * modules whose classes must never be spoofed or shadowed by bundle content — the SDK contract, the
 * runtime core, the server, the CLI, the distribution assembly — while leaving
 * {@code ai.ravenroot.extensions.*} open is the narrower, more defensible cut: it protects exactly
 * the trust-sensitive surface and nothing a legitimate first- or third-party bundle would need.</p>
 *
 * <h2>Two sets, not one</h2>
 * <p>{@link #isReserved} is the <b>build-time refusal</b> set: a bundle's own class may never declare
 * itself under one of these prefixes, checked once by {@code ClassFileOwnName}/{@code
 * PluginBundleValidator} by reading a class file's own constant pool, without ever loading it.
 * {@link #isParentFirst} is the <b>runtime preference</b> set {@link PluginClassLoader} delegates to
 * the host classloader for first: always a superset of {@link #isReserved}, because refusing a bundle
 * from ever declaring a class somewhere is a strictly stronger property than merely preferring the
 * host's version at load time. The two sets do not have to be identical, and they are not:
 * {@code javax.} is parent-first-preferred but not build-time-refused. They still have to be
 * correct relative to each other -- {@code isParentFirst} must never omit anything {@code isReserved}
 * refuses, or the runtime layer would silently allow shadowing something the validator was supposed to
 * make impossible to declare in the first place -- which is why {@code isParentFirst} is defined as
 * {@code isReserved OR javax.}, not as an independently maintained list.</p>
 *
 * <h2>Why not {@code jakarta.}, at either layer</h2>
 * <p>An earlier version of this list reserved {@code javax.} and {@code jakarta.} together, alongside
 * {@code java.}, {@code sun.}, {@code jdk.} and {@code com.sun.}, on the assumption that all five were
 * uniformly "JDK-owned" — the too-narrow framing the principle section above corrects. Applying the
 * corrected question ("does the host provide a real implementation here?") to {@code jakarta.} gives a
 * clear no: neither {@code ravenroot-core} nor {@code ravenroot-application-api} depends on Jakarta Mail
 * or Activation, and the first real bundle — {@code ravenroot-mail}'s Angus Mail
 * dependency — ships 194 real {@code jakarta.mail.*}/{@code jakarta.activation.*} classes with nothing
 * host-provided to defer to. Reserving the namespace anyway refused the dogfooding bundle outright the
 * first time this was actually built end to end, not a theoretical case. {@code jakarta.} therefore
 * stays off <em>both</em> lists: not build-time-refused, and not runtime-preferred either — it is
 * ordinary bundle-private, child-first content, exactly like any other third-party dependency (decision
 * 2), isolated per bundle so two bundles may even carry conflicting Jakarta Mail versions safely.</p>
 *
 * <h2>Why {@code javax.} returns, at the classloader layer only</h2>
 * <p>Applying the same corrected question to {@code javax.} gives a different answer than
 * {@code jakarta.}: the JDK itself ships real implementations under {@code javax.} —
 * {@code javax.crypto}, {@code javax.net}, {@code javax.sql}, among others — so the host <em>does</em>
 * provide something there for a bundle to shadow, even though this repository's own dogfooding bundle
 * happens not to touch it (194 {@code jakarta.*} classes, zero {@code javax.*}, confirmed). That is a
 * runtime shadowing risk, not a build-time declaration risk: a bundle privately shipping its own
 * {@code javax.*}-named utility class is not claiming to be the JDK, so nothing forces the validator to
 * refuse it the way declaring a class under {@code ai.ravenroot.core.*} is refused. What matters is
 * which version <em>resolves</em> when both a host version and a bundle version might exist, and for
 * that, the host's version must always win where one exists — exactly what parent-first delegation
 * already gives every other entry on this list. Where no host version exists (an obscure
 * {@code javax.*} class the JDK does not ship), {@link PluginClassLoader}'s own fallthrough
 * ({@code super.loadClass} failing in the parent, then trying this loader's own {@code findClass})
 * still resolves a genuinely bundle-private {@code javax.*} class, so no capability is lost by making
 * {@code javax.} parent-first again — only the specific case of a bundle successfully shadowing a real
 * JDK {@code javax.*} class is closed. Hence: {@code javax.} joins {@link #isParentFirst} only, never
 * {@link #isReserved}. See {@code PluginClassLoaderTest} for the runtime proof of both halves of this
 * claim (host-provided shadowing prevented; bundle-only content still resolves).</p>
 */
final class ReservedPluginPackages {

    private static final List<String> RESERVED_PREFIXES = List.of(
            "java.",
            "sun.",
            "jdk.",
            "com.sun.",
            "ai.ravenroot.api.",
            "ai.ravenroot.core.",
            "ai.ravenroot.server.",
            "ai.ravenroot.cli.",
            "ai.ravenroot.distribution.");

    // Parent-first-preferred at the classloader layer only -- never added
    // to RESERVED_PREFIXES, so a bundle declaring a class here still passes build-time validation. See
    // this class's own javadoc, "Why javax. returns, at the classloader layer only".
    private static final List<String> PARENT_FIRST_ONLY_PREFIXES = List.of(
            "javax.");

    private ReservedPluginPackages() {
    }

    /**
     * Whether {@code binaryName} (dot-separated, e.g. {@code ai.ravenroot.core.Foo}) falls in a
     * build-time-refused root: a plugin bundle's own class may never declare itself here. Used by
     * {@code PluginBundleValidator}/{@code ClassFileOwnName} at validation time only.
     */
    static boolean isReserved(String binaryName) {
        return matches(binaryName, RESERVED_PREFIXES);
    }

    /**
     * Whether {@code binaryName} falls in a root {@link PluginClassLoader} resolves parent-first,
     * preferring the host's own version over the bundle's when both exist. Always {@code true} when
     * {@link #isReserved} is {@code true} (a strictly stronger guarantee implies the weaker one), plus
     * {@code javax.}, which is preferred here but not build-time-refused. Used by
     * {@code PluginClassLoader} at class-load time only -- never by the validator.
     */
    static boolean isParentFirst(String binaryName) {
        return isReserved(binaryName) || matches(binaryName, PARENT_FIRST_ONLY_PREFIXES);
    }

    private static boolean matches(String binaryName, List<String> prefixes) {
        if (binaryName == null) {
            return false;
        }
        String normalized = binaryName.trim().toLowerCase(Locale.ROOT);
        return prefixes.stream().anyMatch(normalized::startsWith);
    }
}
