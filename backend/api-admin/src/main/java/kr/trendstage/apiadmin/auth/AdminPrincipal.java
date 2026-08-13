package kr.trendstage.apiadmin.auth;

import kr.trendstage.persistence.type.AdminRole;

import java.io.Serializable;
import java.util.UUID;

/** 세션에 담기는 인증 주체. HttpSession 직렬화 대상이므로 Serializable. */
public record AdminPrincipal(UUID id, String loginId, String displayName, AdminRole role) implements Serializable {
}
