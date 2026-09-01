package ai.ravenroot.extensions.jdbc;

/** The JDBC-specific safe basename contract shared by profile parsing and driver lookup. */
public final class JdbcDriverArtifactName {
    static final int MAX_DRIVER_ID_LENGTH = 64;

    private JdbcDriverArtifactName() { }

    public static boolean validDriverId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_DRIVER_ID_LENGTH
                || !asciiAlphaNumeric(value.charAt(0))
                || !asciiAlphaNumeric(value.charAt(value.length() - 1))
                || value.contains("..")) {
            return false;
        }
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (!asciiAlphaNumeric(character) && character != '.' && character != '-'
                    && character != '_') {
                return false;
            }
        }
        return true;
    }

    public static String fileName(String driverId) {
        if (!validDriverId(driverId)) throw new IllegalArgumentException("unsafe JDBC driver id");
        return driverId + ".jar";
    }

    public static boolean matches(String driverId, String fileName) {
        return validDriverId(driverId) && fileName(driverId).equals(fileName);
    }

    /** Entry point used by {@code plugin.sh}; it deliberately accepts a filename, not a path. */
    public static void main(String[] arguments) {
        if (arguments.length == 1 && arguments[0].endsWith(".jar")) {
            String driverId = arguments[0].substring(0, arguments[0].length() - ".jar".length());
            if (matches(driverId, arguments[0])) return;
        }
        System.err.println("JDBC driver artifact name is outside the safe driverId contract");
        System.exit(1);
    }

    private static boolean asciiAlphaNumeric(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9';
    }
}
