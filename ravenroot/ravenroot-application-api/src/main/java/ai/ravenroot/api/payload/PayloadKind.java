package ai.ravenroot.api.payload;

/**
 * The three shapes a Ravenroot payload may take, as named by API-01: scalar, list, map.
 *
 * <p>The kind is part of the declared schema rather than a derived convenience. A caller states the
 * kind it believes it is sending and the receiving side checks that claim against the value, so a
 * client that changes shape without changing its schema version is refused at ingress rather than
 * discovered by a node that assumed otherwise.</p>
 */
public enum PayloadKind {
    /** A null, boolean, numeric, or text payload value. */
    SCALAR,
    /** An ordered payload collection. */
    LIST,
    /** A string-keyed payload object. */
    MAP
}
