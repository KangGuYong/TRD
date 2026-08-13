package kr.trendstage.apipublic.auth;

import com.google.firebase.auth.FirebaseToken;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.repo.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 최초 로그인 시 UserAccount를 자동 생성한다(별도 가입 화면 없음 — Firebase 로그인 성공이 곧 가입).
 * handle은 표시 이름 기반으로 생성하고 중복 시 숫자 접미사를 붙인다.
 */
@Component
public class UserProvisioningService {

    private final UserRepository users;

    public UserProvisioningService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public UUID resolve(FirebaseToken token) {
        return users.findByFirebaseUid(token.getUid())
                .map(UserAccount::getId)
                .orElseGet(() -> provision(token));
    }

    private UUID provision(FirebaseToken token) {
        UserAccount user = new UserAccount(generateHandle(token.getName()), token.getUid());
        return users.save(user).getId();
    }

    private String generateHandle(String displayName) {
        String base = (displayName == null || displayName.isBlank())
                ? "user" : displayName.replaceAll("[^\\p{L}\\p{N}]", "");
        if (base.isBlank()) base = "user";
        if (base.length() > 20) base = base.substring(0, 20);

        String candidate = base;
        int suffix = 0;
        while (users.findByHandle(candidate).isPresent()) {
            candidate = base + "_" + (++suffix);
        }
        return candidate;
    }
}
