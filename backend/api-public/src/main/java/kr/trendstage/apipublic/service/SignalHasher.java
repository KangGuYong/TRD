package kr.trendstage.apipublic.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 제보 기기 ID·IP → HMAC-SHA256(소문자 hex 64자)(SP4 §4). 원문은 어디에도 남기지 않는다 — 이 클래스는
 * 입력을 로그·예외 메시지에 넣지 않는다. 비밀값이 비어 있으면 기동을 막는다(없으면 해시가 사실상 공개 값이 된다).
 */
@Component
public class SignalHasher {

    public static final int MAX_DEVICE_ID = 128;

    private final SecretKeySpec key;

    public SignalHasher(@Value("${signal.hash-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "signal.hash-secret(SIGNAL_HASH_SECRET)이 비어 있습니다 — 제보 기기·IP 해시에 필요합니다(SP4 §4)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** 기기 ID → 해시. 없거나 비면 null. 길이 검사는 호출 측(SubmissionService)이 먼저 한다. */
    public String device(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        return hmac("device:" + deviceId.trim());
    }

    /** 원격 주소 → 해시. IPv4는 주소 전체, IPv6는 앞 /64(휴대폰은 뒤 64비트가 자주 바뀐다). */
    public String ip(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return null;
        return hmac("ip:" + ipKey(remoteAddr.trim()));
    }

    static String ipKey(String addr) {
        if (!addr.contains(":")) return addr;
        try {
            InetAddress a = InetAddress.getByName(addr);   // IP 리터럴이라 DNS 조회가 없다
            if (a instanceof Inet6Address) {
                return HexFormat.of().formatHex(a.getAddress(), 0, 8) + "/64";
            }
            return a.getHostAddress();   // ::ffff:1.2.3.4 → IPv4
        } catch (UnknownHostException e) {
            return addr;
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 쓸 수 없습니다", e);
        }
    }
}
