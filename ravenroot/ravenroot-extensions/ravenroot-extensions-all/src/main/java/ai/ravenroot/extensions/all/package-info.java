/**
 * The dependency-bearing Maven pack for Ravenroot's production first-party node packages.
 *
 * <p>This package intentionally exposes no activation API. Depending on the containing artifact
 * places the extension implementations on the classpath; an embedding application or operator must
 * still register each {@code NodePackage} explicitly through the existing trust boundary.</p>
 */
package ai.ravenroot.extensions.all;
