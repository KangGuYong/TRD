package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.AdminRole;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 감사 로그 (append-only, 해시체인). setter 부재 + {@link Immutable} + DB 트리거(V7)로
 * UPDATE/DELETE를 삼중으로 막는다. prevHash/hash는 {@code audit} 모듈의 HashChainer가 계산해 넘긴다.
 */
@Entity
@Immutable
@Table(name = "admin_audit_log")
public class AdminAuditLogEntry {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role")
    private AdminRole role;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "target_type", length = 40)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail;

    @Column(name = "prev_hash")
    private byte[] prevHash;

    @Column(nullable = false)
    private byte[] hash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminAuditLogEntry() {}

    public static AdminAuditLogEntry of(UUID actorId, AdminRole role, String action, String targetType,
                                         UUID targetId, String detail, byte[] prevHash, byte[] hash,
                                         Instant createdAt) {
        AdminAuditLogEntry e = new AdminAuditLogEntry();
        e.actorId = actorId;
        e.role = role;
        e.action = action;
        e.targetType = targetType;
        e.targetId = targetId;
        e.detail = detail;
        e.prevHash = prevHash;
        e.hash = hash;
        e.createdAt = createdAt;
        return e;
    }

    public Long getId() { return id; }
    public UUID getActorId() { return actorId; }
    public AdminRole getRole() { return role; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
    public Instant getCreatedAt() { return createdAt; }
}
