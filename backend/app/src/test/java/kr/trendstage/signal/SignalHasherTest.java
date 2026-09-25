package kr.trendstage.signal;

import kr.trendstage.apipublic.service.SignalHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalHasherTest {

    private final SignalHasher hasher = new SignalHasher("k1");

    @Test
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> new SignalHasher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SignalHasher("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SignalHasher(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashesAreLowercaseHex64AndNeverTheRawValue() {
        String h = hasher.device("device-abc-123");
        assertThat(h).matches("^[0-9a-f]{64}$").doesNotContain("device-abc");
        assertThat(hasher.ip("203.0.113.7")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void emptyInputsGiveNull() {
        assertThat(hasher.device(null)).isNull();
        assertThat(hasher.device("  ")).isNull();
        assertThat(hasher.ip(null)).isNull();
    }

    @Test
    void deviceIsTrimmedAndDomainSeparatedFromIp() {
        assertThat(hasher.device(" abc ")).isEqualTo(hasher.device("abc"));
        assertThat(hasher.device("abc")).isNotEqualTo(hasher.ip("abc"));
    }

    @Test
    void ipv4UsesWholeAddressIpv6UsesSlash64() {
        assertThat(hasher.ip("203.0.113.7")).isNotEqualTo(hasher.ip("203.0.113.8"));
        assertThat(hasher.ip("2001:db8:1:2:aaaa::1")).isEqualTo(hasher.ip("2001:db8:1:2:bbbb::9"));
        assertThat(hasher.ip("2001:db8:1:2::1")).isNotEqualTo(hasher.ip("2001:db8:1:3::1"));
        assertThat(hasher.ip("::ffff:203.0.113.7")).isEqualTo(hasher.ip("203.0.113.7"));
    }

    @Test
    void secretMatters() {
        assertThat(new SignalHasher("k1").device("x")).isNotEqualTo(new SignalHasher("k2").device("x"));
    }
}
