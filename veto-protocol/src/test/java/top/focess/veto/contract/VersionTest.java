package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void compareToFollowsSemver() {
        assertTrue(Version.parse("1.0.0").compareTo(Version.parse("2.0.0")) < 0);
        assertTrue(Version.parse("2.0.0").compareTo(Version.parse("2.1.0")) < 0);
        assertTrue(Version.parse("2.1.0").compareTo(Version.parse("2.1.1")) < 0);
        // stable outranks pre-release
        assertTrue(Version.parse("1.0.0").compareTo(Version.parse("1.0.0-SNAPSHOT")) > 0);
        assertTrue(Version.parse("1.0.0-SNAPSHOT").compareTo(Version.parse("1.0.0")) < 0);
        // numeric pre-release < alphanumeric
        assertTrue(Version.parse("1.0.0-1").compareTo(Version.parse("1.0.0-alpha")) < 0);
        // numeric compared as integers
        assertTrue(Version.parse("1.0.0-2").compareTo(Version.parse("1.0.0-10")) < 0);
        // fewer identifiers = lower
        assertTrue(Version.parse("1.0.0-alpha").compareTo(Version.parse("1.0.0-alpha.1")) < 0);
        // build metadata ignored
        assertEquals(0, Version.parse("1.0.0+a").compareTo(Version.parse("1.0.0+b")));
    }

    @Test
    void flags() {
        assertTrue(Version.parse("1.0.0").isStable());
        assertFalse(Version.parse("1.0.0-SNAPSHOT").isStable());
        assertTrue(Version.parse("1.0.0-SNAPSHOT").isSnapshot());
        assertFalse(Version.parse("1.0.0-rc.1").isSnapshot());
        assertTrue(Version.UNKNOWN.isUnknown());
        assertFalse(Version.parse("1.0.0").isUnknown());
    }
}
