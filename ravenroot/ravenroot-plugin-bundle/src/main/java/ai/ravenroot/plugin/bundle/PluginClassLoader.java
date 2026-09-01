package ai.ravenroot.plugin.bundle;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * One plugin bundle's own classloader (PLAT-12): parent-first for
 * {@link ReservedPluginPackages reserved packages}, child-first for everything else.
 *
 * <h2>Why not a launcher's flat {@code -cp}</h2>
 * <p>A launcher that recomputed the JVM's own classpath from the plugins directory would put every
 * bundle back on one shared, flat classpath, which has no notion of per-entry delegation order at
 * all -- exactly the auto-scanning, no-isolation failure mode this design rules out, and it could not
 * express "parent-first for these packages, child-first for the rest" no matter how it were
 * constructed. A dedicated classloader per bundle is what makes that policy expressible.</p>
 *
 * <h2>Parent-first for {@code ReservedPluginPackages.isParentFirst}, and why that is not the same
 * list {@code isReserved} checks at build time</h2>
 * <p>Previously, this classloader delegated parent-first for exactly
 * {@link ReservedPluginPackages#isReserved}, the same set the build-time validator refuses a bundle
 * for declaring a class in. The current design splits the two: this classloader delegates parent-first for
 * {@link ReservedPluginPackages#isParentFirst}, a strict superset that also includes {@code javax.} --
 * a prefix the JDK itself ships real implementations under ({@code javax.crypto}, {@code javax.net},
 * {@code javax.sql}, ...) but that the build-time validator does not refuse a bundle for declaring a
 * class in, because doing so is not itself a claim to be the JDK the way declaring a class under
 * {@code ai.ravenroot.core.*} is. See {@code ReservedPluginPackages}'s own javadoc for the full
 * principle ("reserve/prefer what the host provides") and why {@code jakarta.} -- real content in the
 * dogfooding {@code ravenroot-mail} bundle -- correctly stays off both sets while {@code javax.} joins
 * this one only.</p>
 *
 * <h2>Isolation between bundles, for free</h2>
 * <p>Two bundles get two independent {@code PluginClassLoader} instances, siblings under the same
 * parent (the classloader that loaded this class), never each other's ancestor. A class loaded
 * child-first from bundle A's own jars is invisible to bundle B's resolution by construction --
 * nothing has to check for that, it falls out of both loaders delegating to the same shared parent
 * for the packages that must be identical across bundles ({@code NodePackage} itself, the JDK) while
 * keeping everything else private. This is also what lets two bundles carry independent, potentially
 * conflicting versions of the same private dependency safely.</p>
 */
final class PluginClassLoader extends URLClassLoader {

    PluginClassLoader(String pluginId, URL[] bundleJarUrls, ClassLoader parent) {
        super("plugin:" + pluginId, bundleJarUrls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> alreadyLoaded = findLoadedClass(name);
            if (alreadyLoaded != null) {
                return finish(alreadyLoaded, resolve);
            }
            if (ReservedPluginPackages.isParentFirst(name)) {
                // Parent-first: delegates to the host classloader via super.loadClass, which itself
                // falls back to this loader's own findClass (searching the bundle's jars) if the
                // parent does not have the class. So this branch alone is not a runtime guarantee
                // that a bundle can never define a class under a reserved name -- it only prefers the
                // host's version when one already exists there. An earlier version of this comment
                // claimed this path "never even attempts findClass on the bundle's own jars", which
                // is false: super.loadClass can and does reach findClass, as demonstrated by loading a
                // bundle-supplied ai.ravenroot.server.Injected through this exact branch.
                //
                // For isReserved's set (ai.ravenroot.{api,core,server,cli,distribution}., java.,
                // sun., jdk., com.sun.), the actual guarantee is still carried entirely by
                // PluginBundleValidator's build-time constant-pool scan (see ClassFileOwnName), which
                // refuses RESERVED_PACKAGE for any class a bundle declares there before this
                // classloader -- or any classloader -- ever touches the bundle; this branch never
                // actually faces one of those in production. For javax. specifically (isParentFirst
                // but not isReserved), there is no such build-time backstop -- a
                // bundle CAN legitimately ship a class under javax. and pass validation, so this
                // branch is the only mechanism preventing it from shadowing a real JDK javax.* class,
                // by preferring whatever super.loadClass resolves (the JDK's own version, when one
                // exists) over the bundle's. PluginClassLoaderTest proves both halves directly: a
                // host-provided class under a parent-first prefix wins over a differently-compiled
                // same-named class in the bundle jar, and a javax.* class the JDK does NOT ship still
                // resolves from the bundle via this same branch's fallthrough -- no capability lost.
                return finish(super.loadClass(name, false), resolve);
            }
            try {
                // Child-first: the bundle's own jars are searched before falling back to the parent,
                // via URLClassLoader#findClass, which -- unlike loadClass -- never delegates upward.
                return finish(findClass(name), resolve);
            } catch (ClassNotFoundException notInBundle) {
                return finish(super.loadClass(name, false), resolve);
            }
        }
    }

    private Class<?> finish(Class<?> type, boolean resolve) {
        if (resolve) {
            resolveClass(type);
        }
        return type;
    }
}
