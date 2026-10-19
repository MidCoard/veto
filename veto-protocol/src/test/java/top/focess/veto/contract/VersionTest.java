package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void parseRoundTripsToString() {
        assertEquals("1.0.0-SNAPSHOT", Version.parse("1.0.0-SNAPSHOT").toString());
        assertEquals("1.2.0", Version.parse("1.2").toString());
        assertEquals("3.0.0-rc.1", Version.parse("v3.0.0-rc.1").toString());
        assertEquals(
                "1.0.0-beta+exp.sha.5114f85",
                Version.parse("1.0.0-beta+exp.sha.5114f85").toString());
        assertEquals("1.0.0", Version.parse("V1.0.0").toString());
    }

    @Test
    void parseComponents() {
        Version v = Version.parse("1.2.3-rc.1+build.7");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
        assertEquals("rc.1", v.preRelease());
        assertEquals("build.7", v.build());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2.3.4"));
    }
}
