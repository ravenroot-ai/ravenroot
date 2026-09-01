package ai.ravenroot.core.security.egress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Address classification, on IPv4 and IPv6 alike (SEC-10). */
class ReservedNetworkClassificationTest {

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            // IPv4 reserved space
            "127.0.0.1,        LOOPBACK",
            "127.255.255.254,  LOOPBACK",
            "169.254.169.254,  LINK_LOCAL",
            "169.254.0.1,      LINK_LOCAL",
            "10.0.0.1,         PRIVATE",
            "172.16.0.1,       PRIVATE",
            "172.31.255.254,   PRIVATE",
            "192.168.0.1,      PRIVATE",
            "100.64.0.1,       CARRIER_GRADE_NAT",
            "100.127.255.254,  CARRIER_GRADE_NAT",
            "0.0.0.0,          ANY_LOCAL",
            "255.255.255.255,  BROADCAST",
            "224.0.0.1,        MULTICAST",
            // IPv4 public space, including the edges of the private ranges
            "93.184.216.34,    PUBLIC",
            "172.15.255.255,   PUBLIC",
            "172.32.0.1,       PUBLIC",
            "192.167.255.255,  PUBLIC",
            "100.63.255.255,   PUBLIC",
            "100.128.0.1,      PUBLIC",
            "11.0.0.1,         PUBLIC",
            // IPv6
            "::1,              LOOPBACK",
            "::,               ANY_LOCAL",
            "fe80::1,          LINK_LOCAL",
            "fd00::1,          PRIVATE",
            "fc00::1,          PRIVATE",
            "ff02::1,          MULTICAST",
            "2606:2800:220:1:248:1893:25c8:1946, PUBLIC",
    })
    void classifies(String literal, ReservedNetwork expected) throws Exception {
        assertEquals(expected, ReservedNetwork.of(InetAddress.getByName(literal)),
                literal + " must classify as " + expected);
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 is unwrapped, so it cannot launder a reserved destination")
    void ipv4MappedIsUnwrapped() throws Exception {
        // Built by address rather than by name: getByName already normalises the mapped form, and
        // the point of this test is the raw 16-byte answer a hostile resolver could hand back.
        byte[] mappedMetadata = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF,
                (byte) 169, (byte) 254, (byte) 169, (byte) 254};
        assertEquals(ReservedNetwork.LINK_LOCAL,
                ReservedNetwork.of(InetAddress.getByAddress(mappedMetadata)));

        byte[] mappedLoopback = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF,
                127, 0, 0, 1};
        assertEquals(ReservedNetwork.LOOPBACK, ReservedNetwork.of(InetAddress.getByAddress(mappedLoopback)));
    }

    @Test
    @DisplayName("an unclassifiable address is reserved, never public")
    void nullIsTreatedAsReserved() {
        assertTrue(ReservedNetwork.of(null).isReserved(),
                "deny-by-default requires the unclassifiable case to be reserved");
    }

    @Test
    @DisplayName("only PUBLIC is unreserved")
    void onlyPublicIsUnreserved() {
        assertFalse(ReservedNetwork.PUBLIC.isReserved());
        for (ReservedNetwork network : ReservedNetwork.values()) {
            if (network != ReservedNetwork.PUBLIC) {
                assertTrue(network.isReserved(), network + " must count as reserved");
            }
        }
    }
}
