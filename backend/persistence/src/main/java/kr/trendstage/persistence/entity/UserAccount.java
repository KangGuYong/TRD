package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.UserStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** 회원 (users). 앱/콘솔 공용. PII는 별도 테이블(미구현). */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String handle;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)          // PG 네이티브 enum 매핑
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected UserAccount() {}

    public UserAccount(String handle) { this.handle = handle; }

    public UUID getId() { return id; }
    public String getHandle() { return handle; }
    public UserStatus getStatus() { return status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }

    public void suspend() { this.status = UserStatus.SUSPENDED; }
    public void verify(Instant at) { this.verifiedAt = at; }
}
