# B1 · 신고 콘텐츠 큐 (ADM-410) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 유저가 트렌드 카드를 신고할 수 있게 하고, 검수자(REVIEWER+)가 "즉시 비공개" 또는 "공개 유지·소명 요청" 중 하나를 선택해 처리하며, 운영자(OPERATOR+)가 소명을 검토해 최종 결정(복원/영구비공개/수정후복원)을 내리는 ADM-410 신고 큐를 만든다.

**Architecture:** 신고 대상은 `TrendItem`(유저가 볼 수 있는 유일한 단위)이지만, 처리 과정에서 관리자가 그 트렌드의 제보 원문 목록에서 실제 문제 제보(`Submission`)를 직접 지목한다 — 최초 제보자를 자동 추정하지 않는다. `TrendItem`에 `visibility`(PUBLIC/TEMP_HIDDEN/PERMANENT_HIDDEN)라는 새 독립 필드를 추가해 판정(`TrendState`)·점수 파이프라인과 완전히 분리한다(R2: 비공개돼도 채점은 그대로 진행). `Report`는 `OPEN → EXPLAINING(1차 처리) → DECIDED(최종 결정)` 상태 머신이며, 4시간 자동 임시비공개는 의도적으로 구현하지 않는다(오신고로 정상 콘텐츠가 사람 개입 없이 비공개되는 것을 막기 위함).

**Tech Stack:** Spring Boot 3.3.5 / Java 17, JPA, PostgreSQL(Flyway V26), React + TypeScript + TanStack Query(admin), React Native + Expo + TanStack Query(app).

**설계 문서:** `docs/superpowers/specs/2026-08-20-report-queue-design.md`

**테스트 전략:** 이 저장소는 순수 함수(`domain-core`, `merge` 모듈)만 JUnit 단위 테스트로 검증한다. `api-admin`/`api-public`은 기존 관례상 테스트 디렉터리 자체가 없다(`docs/superpowers/plans/2026-08-20-admin-approvals.md`와 동일 관례) — 컴파일 확인 + 앱 기동 후 curl/psql/브라우저 수동 검증으로 확인한다. `Report`/`TrendItem.visibility`의 상태 전이는 글루 코드(엔티티는 상태를 저장만 하고, 검증은 서비스 계층 `require*` 헬퍼가 담당 — `MergeQueueController.requirePending()`/`ApprovalService.requirePending()`과 동일 패턴)라 신규 JUnit 테스트 파일은 없다. 마지막 백엔드 태스크에서 기존 `domain-core`/`merge`/`audit` 테스트가 깨지지 않았는지 회귀 확인한다.

---

### Task 1: persistence — DB 마이그레이션 V26 (`reports` 테이블 + `trend_items.visibility`)

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V26__reports.sql`

- [ ] **Step 1: 파일 작성**

```sql
-- V26 · 신고 콘텐츠 큐 (ADM-410, B1)
-- 신고 대상은 trend_items(유저가 볼 수 있는 유일한 단위). 처리 과정에서 관리자가 특정
-- submission을 지목함으로써 실질적으로 "제보자를 신고"하는 효과를 낸다(설계서 §아키텍처).
-- 4h 자동 임시비공개는 의도적으로 넣지 않는다 — 오신고로 정상 콘텐츠가 사람 개입 없이
-- 비공개되는 것을 막기 위함(설계서 §범위).

CREATE TYPE trend_visibility AS ENUM ('PUBLIC', 'TEMP_HIDDEN', 'PERMANENT_HIDDEN');
ALTER TABLE trend_items ADD COLUMN visibility trend_visibility NOT NULL DEFAULT 'PUBLIC';
COMMENT ON COLUMN trend_items.visibility IS '신고 처리 결과의 표시 계층. 판정/점수 파이프라인과 완전히 분리(R2) — 비공개돼도 채점은 그대로 진행.';

CREATE TYPE report_status   AS ENUM ('OPEN', 'EXPLAINING', 'DECIDED');
CREATE TYPE report_reason   AS ENUM ('DEFAMATION', 'BUSINESS_INTERFERENCE', 'OTHER');
CREATE TYPE report_decision AS ENUM ('RESTORE', 'HIDE_PERMANENT', 'EDIT_RESTORE');

CREATE TABLE reports (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_item_id            UUID            NOT NULL REFERENCES trend_items(id),
    reporter_id              UUID            NOT NULL REFERENCES users(id),
    reason                   report_reason   NOT NULL,
    detail                   TEXT,
    status                   report_status   NOT NULL DEFAULT 'OPEN',
    submission_id            UUID            REFERENCES submissions(id),
    explanation_deadline     TIMESTAMPTZ,
    explanation_text         TEXT,
    explanation_submitted_at TIMESTAMPTZ,
    decision                 report_decision,
    decision_note            TEXT,
    decided_by               UUID            REFERENCES admin_accounts(id),
    decided_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now()
);
COMMENT ON COLUMN reports.reporter_id IS '신고자 비식별 원칙: 관리자 API 응답 DTO에는 이 필드를 절대 넣지 않는다(컨트롤러 레이어에서 강제).';
COMMENT ON COLUMN reports.submission_id IS '1차 처리(hide/request-explanation) 시 관리자가 지목. 그 전엔 NULL — 최초 제보자를 자동 추정하지 않는다.';

CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_trend_item_id ON reports(trend_item_id);
CREATE INDEX idx_reports_submission_id ON reports(submission_id);
```

- [ ] **Step 2: 컴파일 확인(마이그레이션 문법 오류는 앱 기동 시 Flyway가 검증하므로 여기서는 스킵, Task 15에서 전체 기동 확인)**

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V26__reports.sql
git commit -m "feat(persistence): add V26 migration — reports table + trend_items.visibility"
```

---

### Task 2: persistence — 신규 enum 타입 4개

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/TrendVisibility.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/ReportStatus.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/ReportReason.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/type/ReportDecision.java`

- [ ] **Step 1: 파일 4개 작성**

```java
package kr.trendstage.persistence.type;

/** trend_items.visibility (PG enum trend_visibility). 신고 처리 결과의 표시 계층 — 판정/점수와 분리(R2). */
public enum TrendVisibility { PUBLIC, TEMP_HIDDEN, PERMANENT_HIDDEN }
```

```java
package kr.trendstage.persistence.type;

/** reports.status (PG enum report_status). OPEN → EXPLAINING(1차 처리) → DECIDED(최종 결정). */
public enum ReportStatus { OPEN, EXPLAINING, DECIDED }
```

```java
package kr.trendstage.persistence.type;

/** reports.reason (PG enum report_reason). */
public enum ReportReason { DEFAMATION, BUSINESS_INTERFERENCE, OTHER }
```

```java
package kr.trendstage.persistence.type;

/** reports.decision (PG enum report_decision). EDIT_RESTORE는 newCanonicalName 필수(서비스 계층 검증). */
public enum ReportDecision { RESTORE, HIDE_PERMANENT, EDIT_RESTORE }
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/type/TrendVisibility.java backend/persistence/src/main/java/kr/trendstage/persistence/type/ReportStatus.java backend/persistence/src/main/java/kr/trendstage/persistence/type/ReportReason.java backend/persistence/src/main/java/kr/trendstage/persistence/type/ReportDecision.java
git commit -m "feat(persistence): add TrendVisibility/ReportStatus/ReportReason/ReportDecision enums"
```

---

### Task 3: persistence — `TrendItem`에 `visibility` 필드 추가

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/TrendItem.java`

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.persistence.type.TrendState;
import kr.trendstage.persistence.type.TrendVisibility;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 병합된 트렌드 항목(클러스터) = 판정 단위.
 * 낙관적 락(version)으로 검수자 동시 병합 충돌 감지(03 §6).
 * first_seen_at 변경 시 baseline 재계산을 반드시 트리거(03 §3.2) — 서비스 계층 책임.
 * embedding(vector)은 JPA로 매핑하지 않고 MergeService의 네이티브 pgvector 쿼리로 다룬다.
 */
@Entity
@Table(name = "trend_items")
public class TrendItem {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "canonical_name", nullable = false, length = 120)
    private String canonicalName;

    @Column(name = "normalized_key", nullable = false, length = 160)
    private String normalizedKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "aliases", columnDefinition = "text[]", nullable = false)
    private String[] aliases = new String[0];

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TrendCategory category;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TrendState state = TrendState.DRAFT;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    /** 병합 패자 → 승자 리다이렉트(tombstone). DELETE 금지(03 §4.2). */
    @Column(name = "merged_into")
    private UUID mergedInto;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** cluster_merge 배치가 임베딩 유사도 비교를 마친 시점. NULL이면 아직 미검토(03 §2③). */
    @Column(name = "merge_checked_at")
    private Instant mergeCheckedAt;

    /** ADM-200 판정 유예 연장. NULL이면 기본 D+14. 최대 D+21(firstSeenAt+21일)까지만 허용(서비스 계층 검증). */
    @Column(name = "judgment_deadline_override")
    private Instant judgmentDeadlineOverride;

    /** 신고 처리 결과의 표시 계층(ADM-410, B1). 판정/점수 파이프라인과 완전히 분리(R2) — 비공개돼도 채점은 그대로 진행. */
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private TrendVisibility visibility = TrendVisibility.PUBLIC;

    protected TrendItem() {}

    public TrendItem(String canonicalName, String normalizedKey, TrendCategory category, Instant firstSeenAt) {
        this.canonicalName = canonicalName;
        this.normalizedKey = normalizedKey;
        this.category = category;
        this.firstSeenAt = firstSeenAt;
    }

    public UUID getId() { return id; }
    public String getCanonicalName() { return canonicalName; }
    public String getNormalizedKey() { return normalizedKey; }
    public String[] getAliases() { return aliases; }
    public TrendCategory getCategory() { return category; }
    public TrendState getState() { return state; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public UUID getMergedInto() { return mergedInto; }
    public int getVersion() { return version; }
    public Instant getMergeCheckedAt() { return mergeCheckedAt; }
    public void markMergeChecked(Instant at) { this.mergeCheckedAt = at; }
    public Instant getJudgmentDeadlineOverride() { return judgmentDeadlineOverride; }
    public void extendJudgmentDeadline(Instant newDeadline) { this.judgmentDeadlineOverride = newDeadline; }
    public TrendVisibility getVisibility() { return visibility; }

    public void transitionTo(TrendState next) { this.state = next; }

    /** ADM-410 신고 처리(hide/decide)가 호출. 판정 엔진·score_ledger와 무관한 순수 표시 계층 변경(R2). */
    public void applyVisibility(TrendVisibility next) { this.visibility = next; }

    /** 병합 패자로 표기. state=MERGED와 merged_into는 함께여야 한다(DDL CHECK). */
    public void mergeInto(UUID survivorId) {
        this.state = TrendState.MERGED;
        this.mergedInto = survivorId;
    }

    /** first_seen_at 앞당김(병합) — 호출 측이 baseline 재계산 잡을 트리거해야 함. */
    public void pullForwardFirstSeen(Instant earlier) {
        if (earlier.isBefore(this.firstSeenAt)) this.firstSeenAt = earlier;
    }

    public void setAliases(String[] aliases) { this.aliases = aliases; }
    public void setCanonicalName(String name) { this.canonicalName = name; }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/TrendItem.java
git commit -m "feat(persistence): add TrendItem.visibility + applyVisibility"
```

---

### Task 4: persistence — `TrendItemRepository`에 visibility 필터 조회 추가

**Files:**
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/TrendItemRepository.java`

- [ ] **Step 1: 파일 전체 교체**

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.type.TrendState;
import kr.trendstage.persistence.type.TrendVisibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrendItemRepository extends JpaRepository<TrendItem, UUID> {

    /** 완전일치 병합 (03 §2②). */
    Optional<TrendItem> findByNormalizedKey(String normalizedKey);

    /** 배치 대상: 관측 중 항목만. */
    List<TrendItem> findByStateIn(List<TrendState> states);

    /** cluster_merge 배치 대상: 아직 임베딩 유사도 비교를 안 한 활성 항목(03 §2③). */
    List<TrendItem> findByStateInAndMergeCheckedAtIsNull(List<TrendState> states);

    /** api-public 공개 조회 필터링용 — visibility=PUBLIC만 노출(신고 처리 결과 반영, ADM-410). */
    List<TrendItem> findByStateInAndVisibility(List<TrendState> states, TrendVisibility visibility);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/TrendItemRepository.java
git commit -m "feat(persistence): add findByStateInAndVisibility to TrendItemRepository"
```

---

### Task 5: persistence — `Report` 엔티티 신규

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Report.java`

상태 전이 검증(잘못된 전이 차단)은 이 엔티티가 아니라 서비스 계층(`ReportAdminService`)의 책임이다 — `TrendItem.transitionTo()`/`ApprovalRequest`와 동일한 관례(엔티티는 상태를 저장만 함).

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import kr.trendstage.persistence.type.ReportDecision;
import kr.trendstage.persistence.type.ReportReason;
import kr.trendstage.persistence.type.ReportStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 신고. 상태전이는 OPEN → EXPLAINING(1차 처리, submission_id 지목) → DECIDED(최종 결정).
 * 상태 검증은 서비스 계층(ReportAdminService)의 책임 — 다른 엔티티(TrendItem 등)와 동일 관례.
 * reporter_id는 저장하되 관리자 API 응답 DTO에는 절대 노출하지 않는다(신고자 비식별 원칙,
 * 컨트롤러 레이어에서 강제 — V26 컬럼 코멘트 참고).
 */
@Entity
@Table(name = "reports")
public class Report {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trend_item_id", nullable = false)
    private UUID trendItemId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ReportReason reason;

    @Column
    private String detail;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ReportStatus status = ReportStatus.OPEN;

    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "explanation_deadline")
    private Instant explanationDeadline;

    @Column(name = "explanation_text")
    private String explanationText;

    @Column(name = "explanation_submitted_at")
    private Instant explanationSubmittedAt;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column
    private ReportDecision decision;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Report() {}

    public Report(UUID trendItemId, UUID reporterId, ReportReason reason, String detail) {
        this.trendItemId = trendItemId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.detail = detail;
    }

    /** 1차 처리(hide 또는 request-explanation 공통). hide 여부에 따른 TrendItem.visibility 변경은 호출 측(서비스 계층)이 별도로 수행. */
    public void moveToExplaining(UUID submissionId, Instant deadline) {
        this.submissionId = submissionId;
        this.explanationDeadline = deadline;
        this.status = ReportStatus.EXPLAINING;
    }

    public void submitExplanation(String text, Instant now) {
        this.explanationText = text;
        this.explanationSubmittedAt = now;
    }

    public void decide(ReportDecision decision, String note, UUID decidedBy, Instant now) {
        this.decision = decision;
        this.decisionNote = note;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.status = ReportStatus.DECIDED;
    }

    public UUID getId() { return id; }
    public UUID getTrendItemId() { return trendItemId; }
    public UUID getReporterId() { return reporterId; }
    public ReportReason getReason() { return reason; }
    public String getDetail() { return detail; }
    public ReportStatus getStatus() { return status; }
    public UUID getSubmissionId() { return submissionId; }
    public Instant getExplanationDeadline() { return explanationDeadline; }
    public String getExplanationText() { return explanationText; }
    public Instant getExplanationSubmittedAt() { return explanationSubmittedAt; }
    public ReportDecision getDecision() { return decision; }
    public String getDecisionNote() { return decisionNote; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/Report.java
git commit -m "feat(persistence): add Report entity (OPEN/EXPLAINING/DECIDED state)"
```

---

### Task 6: persistence — `ReportRepository` 신규

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/ReportRepository.java`

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.type.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** ADM-410 큐 — 처리 중인 것만(DECIDED는 큐에서 빠짐). */
    List<Report> findByStatusInOrderByCreatedAtAsc(List<ReportStatus> statuses);

    /** /v1/reports/me — 내가 접수한 신고. */
    List<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    /** /v1/reports/received — 나를 지목해 소명을 요구한 신고(내 submission을 지목한 것들). */
    List<Report> findBySubmissionIdInOrderByCreatedAtDesc(List<UUID> submissionIds);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :persistence:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/repo/ReportRepository.java
git commit -m "feat(persistence): add ReportRepository"
```

---

### Task 7: api-public — 신고 API DTO 4종

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportCreateRequest.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ExplanationRequest.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportMineResponse.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportReceivedResponse.java`

- [ ] **Step 1: 파일 4개 작성**

```java
package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.trendstage.persistence.type.ReportReason;

import java.util.UUID;

/** OpenAPI ReportCreate 대응. */
public record ReportCreateRequest(
        @NotNull UUID trendItemId,
        @NotNull ReportReason reason,
        @Size(max = 1000) String detail
) {}
```

```java
package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /v1/reports/{id}/explanation 요청 본문. */
public record ExplanationRequest(@NotBlank @Size(max = 2000) String text) {}
```

```java
package kr.trendstage.apipublic.web;

import java.time.Instant;
import java.util.UUID;

/** OpenAPI ReportMine 대응 — 내가 접수한 신고. */
public record ReportMineResponse(
        UUID id,
        UUID trendItemId,
        String status,        // OPEN|EXPLAINING|DECIDED
        String reason,
        String decision,      // null 가능
        String decisionNote,  // null 가능
        Instant createdAt,
        Instant decidedAt     // null 가능
) {}
```

```java
package kr.trendstage.apipublic.web;

import java.time.Instant;
import java.util.UUID;

/** OpenAPI ReportReceived 대응 — 나를 지목해 소명을 요구한 신고. */
public record ReportReceivedResponse(
        UUID id,
        UUID trendItemId,
        String reason,
        String detail,
        Instant explanationDeadline,
        boolean explanationSubmitted,
        String status
) {}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportCreateRequest.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ExplanationRequest.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportMineResponse.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportReceivedResponse.java
git commit -m "feat(api-public): add report DTOs (ReportCreate/Explanation/ReportMine/ReportReceived)"
```

---

### Task 8: api-public — 신고 API 예외 3종

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportNotFoundException.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ExplanationForbiddenException.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ExplanationConflictException.java`

- [ ] **Step 1: 파일 3개 작성**

```java
package kr.trendstage.apipublic.web;

/** 존재하지 않는 신고(404). */
public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(String message) { super(message); }
}
```

```java
package kr.trendstage.apipublic.web;

/** 소명 대상으로 지목되지 않은 유저의 소명 제출 시도(403). */
public class ExplanationForbiddenException extends RuntimeException {
    public ExplanationForbiddenException(String message) { super(message); }
}
```

```java
package kr.trendstage.apipublic.web;

/** EXPLAINING 상태가 아닌 신고에 소명을 제출하려는 시도(409). */
public class ExplanationConflictException extends RuntimeException {
    public ExplanationConflictException(String message) { super(message); }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportNotFoundException.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ExplanationForbiddenException.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/ExplanationConflictException.java
git commit -m "feat(api-public): add report exceptions (NotFound/Forbidden/Conflict)"
```

---

### Task 9: api-public — `ReportService`

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/ReportService.java`

**주의:** 신고 처리(1차 처리·최종 결정)는 관리자 전용이라 이 서비스에는 없다 — Task 13의 `ReportAdminService`(api-admin)가 담당한다.

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.ExplanationConflictException;
import kr.trendstage.apipublic.web.ExplanationForbiddenException;
import kr.trendstage.apipublic.web.ReportCreateRequest;
import kr.trendstage.apipublic.web.ReportMineResponse;
import kr.trendstage.apipublic.web.ReportNotFoundException;
import kr.trendstage.apipublic.web.ReportReceivedResponse;
import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 신고 접수/조회/소명 제출. 신고 대상은 TrendItem(유저가 볼 수 있는 유일한 단위) — 개별
 * 제보 원문에는 접근할 수 없다(설계서 §범위). 신고 처리는 관리자 전용이라 여기 없음.
 */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;

    public ReportService(ReportRepository reports, TrendItemRepository trends, SubmissionRepository submissions) {
        this.reports = reports;
        this.trends = trends;
        this.submissions = submissions;
    }

    @Transactional
    public ReportMineResponse create(UUID reporterId, ReportCreateRequest req) {
        TrendItem item = trends.findById(req.trendItemId())
                .filter(i -> i.getState() != TrendState.MERGED)
                .orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));
        Report report = reports.save(new Report(item.getId(), reporterId, req.reason(), req.detail()));
        return toMineResponse(report);
    }

    @Transactional(readOnly = true)
    public List<ReportMineResponse> mine(UUID reporterId) {
        return reports.findByReporterIdOrderByCreatedAtDesc(reporterId).stream()
                .map(this::toMineResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportReceivedResponse> received(UUID userId) {
        List<UUID> mySubmissionIds = submissions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Submission::getId)
                .toList();
        if (mySubmissionIds.isEmpty()) return List.of();
        return reports.findBySubmissionIdInOrderByCreatedAtDesc(mySubmissionIds).stream()
                .map(this::toReceivedResponse)
                .toList();
    }

    @Transactional
    public void submitExplanation(UUID userId, UUID reportId, String text) {
        Report report = reports.findById(reportId)
                .orElseThrow(() -> new ReportNotFoundException("존재하지 않는 신고입니다"));
        if (report.getSubmissionId() == null || !isOwnedBy(report.getSubmissionId(), userId)) {
            throw new ExplanationForbiddenException("소명 대상으로 지목되지 않았습니다");
        }
        if (report.getStatus() != ReportStatus.EXPLAINING) {
            throw new ExplanationConflictException("소명을 제출할 수 있는 상태가 아닙니다: " + report.getStatus());
        }
        report.submitExplanation(text, Instant.now());
    }

    private boolean isOwnedBy(UUID submissionId, UUID userId) {
        return submissions.findById(submissionId).map(Submission::getUserId).map(userId::equals).orElse(false);
    }

    private ReportMineResponse toMineResponse(Report r) {
        return new ReportMineResponse(r.getId(), r.getTrendItemId(), r.getStatus().name(), r.getReason().name(),
                r.getDecision() == null ? null : r.getDecision().name(), r.getDecisionNote(),
                r.getCreatedAt(), r.getDecidedAt());
    }

    private ReportReceivedResponse toReceivedResponse(Report r) {
        return new ReportReceivedResponse(r.getId(), r.getTrendItemId(), r.getReason().name(), r.getDetail(),
                r.getExplanationDeadline(), r.getExplanationSubmittedAt() != null, r.getStatus().name());
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/ReportService.java
git commit -m "feat(api-public): add ReportService (create/mine/received/submitExplanation)"
```

---

### Task 10: api-public — `ReportController`

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportController.java`

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apipublic.web;

import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 신고 API. OpenAPI /v1/reports 대응. 인증 필요(PublicSecurityConfig). */
@RestController
@RequestMapping("/v1/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ReportMineResponse> create(Authentication auth, @Valid @RequestBody ReportCreateRequest req) {
        var response = service.create(userId(auth), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public List<ReportMineResponse> mine(Authentication auth) {
        return service.mine(userId(auth));
    }

    @GetMapping("/received")
    public List<ReportReceivedResponse> received(Authentication auth) {
        return service.received(userId(auth));
    }

    @PostMapping("/{id}/explanation")
    public ResponseEntity<Void> explanation(@PathVariable UUID id, Authentication auth,
                                             @Valid @RequestBody ExplanationRequest req) {
        service.submitExplanation(userId(auth), id, req.text());
        return ResponseEntity.ok().build();
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/ReportController.java
git commit -m "feat(api-public): add ReportController (create/me/received/explanation)"
```

---

### Task 11: api-public — 신고 예외를 `ApiExceptionHandler`에 매핑

**Files:**
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java`

- [ ] **Step 1: `PreferencesNotFoundException` 핸들러 다음에 3개 핸들러 추가**

`@ExceptionHandler(PreferencesNotFoundException.class)` 블록(`return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));` 로 끝나는 부분) 바로 다음, `private Map<String, Object> problem(...)` 메서드 앞에 삽입:

```java
    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(ReportNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }

    @ExceptionHandler(ExplanationForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handle(ExplanationForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, e.getMessage()));
    }

    @ExceptionHandler(ExplanationConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(ExplanationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/web/ApiExceptionHandler.java
git commit -m "feat(api-public): map report exceptions to 404/403/409"
```

---

### Task 12: api-public — `TrendQueryService`가 `visibility=PUBLIC`만 노출하도록 필터링

**Files:**
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java`

**목적:** 신고 처리로 `TEMP_HIDDEN`/`PERMANENT_HIDDEN`이 된 트렌드가 홈 목록·오늘의 5개·상세 조회 어디에도 나타나지 않게 한다(설계서: "둘 다 공개 화면에서 동일하게 제외").

- [ ] **Step 1: import 추가**

`import kr.trendstage.persistence.type.TrendState;` 바로 다음 줄에 추가:

```java
import kr.trendstage.persistence.type.TrendVisibility;
```

- [ ] **Step 2: `liveRanked()` 수정**

기존:

```java
    private List<TrendSummaryResponse> liveRanked() {
        return trends.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING))
                .stream()
```

교체:

```java
    private List<TrendSummaryResponse> liveRanked() {
        return trends.findByStateInAndVisibility(List.of(TrendState.PENDING, TrendState.JUDGING), TrendVisibility.PUBLIC)
                .stream()
```

- [ ] **Step 3: `generateSelection()` 수정 (오늘의 5개 후보군도 동일 필터)**

기존:

```java
        List<TrendItem> candidates = trends.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING));
```

교체:

```java
        List<TrendItem> candidates = trends.findByStateInAndVisibility(List.of(TrendState.PENDING, TrendState.JUDGING), TrendVisibility.PUBLIC);
```

- [ ] **Step 4: `detail()` 수정**

기존:

```java
        TrendItem item = trends.findById(id)
                .filter(i -> i.getState() != TrendState.MERGED)
                .orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));
```

교체:

```java
        TrendItem item = trends.findById(id)
                .filter(i -> i.getState() != TrendState.MERGED)
                .filter(i -> i.getVisibility() == TrendVisibility.PUBLIC)
                .orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));
```

- [ ] **Step 5: 컴파일 확인**

Run: `cd backend && ./gradlew :api-public:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java
git commit -m "fix(api-public): exclude non-PUBLIC trend items from home/detail/daily-selection"
```

---

### Task 13: api-admin — `ReportAdminService`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/report/ReportAdminService.java`

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apiadmin.report;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.ReportDecision;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendVisibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-410 신고 콘텐츠 큐. 4h 자동 임시비공개는 의도적으로 없음 — 사람이 명시적으로 판단하기
 * 전까지는 절대 비공개되지 않는다(설계서 §범위). visibility는 순수 표시 계층 — 판정/점수와 분리(R2).
 */
@Service
public class ReportAdminService {

    private static final Duration EXPLANATION_WINDOW = Duration.ofHours(48);

    private final ReportRepository reports;
    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ReportAdminService(ReportRepository reports, TrendItemRepository trends, SubmissionRepository submissions,
                               AuditLogService auditLogService, Clock clock) {
        this.reports = reports;
        this.trends = trends;
        this.submissions = submissions;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Report> queue() {
        return reports.findByStatusInOrderByCreatedAtAsc(List.of(ReportStatus.OPEN, ReportStatus.EXPLAINING));
    }

    /** 관리자가 소명 대상을 지목할 후보 — 병합검수 화면과 동일 뷰(자동 추정 없음). */
    @Transactional(readOnly = true)
    public List<Submission> candidateSubmissions(UUID reportId) {
        Report report = requireReport(reportId);
        return submissions.findByTrendItemIdAndResultNot(report.getTrendItemId(), SubmissionResult.VOID).stream()
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .toList();
    }

    @Transactional
    public Report hide(UUID reportId, UUID submissionId, String note, AdminPrincipal actor) {
        Report report = requireOpen(reportId);
        requireSubmissionBelongsToReport(report, submissionId);

        TrendItem item = trends.findById(report.getTrendItemId()).orElseThrow();
        item.applyVisibility(TrendVisibility.TEMP_HIDDEN);

        report.moveToExplaining(submissionId, clock.instant().plus(EXPLANATION_WINDOW));
        auditLogService.record(actor.id(), actor.role(), "REPORT_HIDE", "REPORT", reportId, Map.of(
                "trendItemId", report.getTrendItemId().toString(), "submissionId", submissionId.toString(),
                "note", note == null ? "" : note));
        return report;
    }

    @Transactional
    public Report requestExplanation(UUID reportId, UUID submissionId, String note, AdminPrincipal actor) {
        Report report = requireOpen(reportId);
        requireSubmissionBelongsToReport(report, submissionId);

        report.moveToExplaining(submissionId, clock.instant().plus(EXPLANATION_WINDOW));
        auditLogService.record(actor.id(), actor.role(), "REPORT_REQUEST_EXPLANATION", "REPORT", reportId, Map.of(
                "trendItemId", report.getTrendItemId().toString(), "submissionId", submissionId.toString(),
                "note", note == null ? "" : note));
        return report;
    }

    @Transactional
    public Report decide(UUID reportId, ReportDecision decision, String note, String newCanonicalName, AdminPrincipal actor) {
        Report report = requireExplaining(reportId);
        if (decision == ReportDecision.EDIT_RESTORE && (newCanonicalName == null || newCanonicalName.isBlank())) {
            throw new AdminValidationException("EDIT_RESTORE는 newCanonicalName이 필수입니다");
        }

        TrendItem item = trends.findById(report.getTrendItemId()).orElseThrow();
        switch (decision) {
            case RESTORE -> item.applyVisibility(TrendVisibility.PUBLIC);
            case HIDE_PERMANENT -> item.applyVisibility(TrendVisibility.PERMANENT_HIDDEN);
            case EDIT_RESTORE -> {
                item.setCanonicalName(newCanonicalName);
                item.applyVisibility(TrendVisibility.PUBLIC);
            }
        }

        report.decide(decision, note, actor.id(), clock.instant());
        auditLogService.record(actor.id(), actor.role(), "REPORT_DECIDE", "REPORT", reportId, Map.of(
                "trendItemId", report.getTrendItemId().toString(), "decision", decision.name(),
                "note", note == null ? "" : note));
        return report;
    }

    private Report requireReport(UUID id) {
        return reports.findById(id).orElseThrow(() -> new AdminValidationException("존재하지 않는 신고입니다"));
    }

    private Report requireOpen(UUID id) {
        Report report = requireReport(id);
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new AdminValidationException("이미 1차 처리된 신고입니다: " + report.getStatus());
        }
        return report;
    }

    private Report requireExplaining(UUID id) {
        Report report = requireReport(id);
        if (report.getStatus() != ReportStatus.EXPLAINING) {
            throw new AdminValidationException("소명 대기 상태가 아닙니다: " + report.getStatus());
        }
        return report;
    }

    private void requireSubmissionBelongsToReport(Report report, UUID submissionId) {
        Submission submission = submissions.findById(submissionId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 제보입니다"));
        if (!submission.getTrendItemId().equals(report.getTrendItemId())) {
            throw new AdminValidationException("해당 신고의 트렌드 항목에 속하지 않는 제보입니다");
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/report/ReportAdminService.java
git commit -m "feat(api-admin): add ReportAdminService (queue/hide/request-explanation/decide)"
```

---

### Task 14: api-admin — `ReportController`

**Files:**
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ReportController.java`

권한(설계서 §api-admin): 큐 조회·1차 처리(hide/request-explanation)는 REVIEWER 이상, 최종 결정(decide)은 OPERATOR 이상. 신고자 비식별 원칙상 응답 DTO에 `reporterId`를 절대 넣지 않는다.

- [ ] **Step 1: 파일 작성**

```java
package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.report.ReportAdminService;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.ReportDecision;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * ADM-410 신고 콘텐츠 큐. 권한(02 §1.1): 1차 처리(hide/request-explanation)는 REVIEWER 이상,
 * 최종 결정(decide)은 OPERATOR 이상. 신고자 비식별 원칙 — 응답 DTO에 reporterId를 절대 넣지 않는다.
 */
@RestController
@RequestMapping("/admin/reports")
public class ReportController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ReportAdminService service;
    private final UserRepository users;

    public ReportController(ReportAdminService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    public record ReportResponse(String id, String trendItemId, String reason, String detail, String status,
                                  String submissionId, String explanationDeadline, String explanationText,
                                  String decision, String decisionNote, String createdAt) {}
    public record SubmissionCandidate(String submissionId, String handle, String rawInput, String oneLine,
                                       String evidenceUrl, String createdAt) {}
    public record TriageRequest(UUID submissionId, String note) {}
    public record DecideRequest(ReportDecision decision, String note, String newCanonicalName) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public List<ReportResponse> queue() {
        return service.queue().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}/submissions")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public List<SubmissionCandidate> submissions(@PathVariable UUID id) {
        return service.candidateSubmissions(id).stream().map(this::toCandidate).toList();
    }

    @PostMapping("/{id}/hide")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public ReportResponse hide(@PathVariable UUID id, @RequestBody TriageRequest req,
                                @AuthenticationPrincipal AdminPrincipal actor) {
        requireSubmissionId(req);
        return toResponse(service.hide(id, req.submissionId(), req.note(), actor));
    }

    @PostMapping("/{id}/request-explanation")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN')")
    public ReportResponse requestExplanation(@PathVariable UUID id, @RequestBody TriageRequest req,
                                              @AuthenticationPrincipal AdminPrincipal actor) {
        requireSubmissionId(req);
        return toResponse(service.requestExplanation(id, req.submissionId(), req.note(), actor));
    }

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ReportResponse decide(@PathVariable UUID id, @RequestBody DecideRequest req,
                                  @AuthenticationPrincipal AdminPrincipal actor) {
        if (req.decision() == null) {
            throw new AdminValidationException("decision은 필수입니다");
        }
        return toResponse(service.decide(id, req.decision(), req.note(), req.newCanonicalName(), actor));
    }

    private void requireSubmissionId(TriageRequest req) {
        if (req.submissionId() == null) {
            throw new AdminValidationException("submissionId는 필수입니다 — 신고 대상 제보를 지목해야 합니다");
        }
    }

    private ReportResponse toResponse(Report r) {
        return new ReportResponse(
                r.getId().toString(), r.getTrendItemId().toString(), r.getReason().name(), r.getDetail(),
                r.getStatus().name(), r.getSubmissionId() == null ? null : r.getSubmissionId().toString(),
                r.getExplanationDeadline() == null ? null : DISPLAY_FORMAT.format(r.getExplanationDeadline()),
                r.getExplanationText(),
                r.getDecision() == null ? null : r.getDecision().name(), r.getDecisionNote(),
                DISPLAY_FORMAT.format(r.getCreatedAt()));
    }

    private SubmissionCandidate toCandidate(Submission s) {
        String handle = users.findById(s.getUserId()).map(UserAccount::getHandle).orElse("(탈퇴)");
        return new SubmissionCandidate(s.getId().toString(), handle, s.getRawInput(), s.getOneLine(),
                s.getEvidenceUrl(), DISPLAY_FORMAT.format(s.getCreatedAt()));
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :api-admin:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ReportController.java
git commit -m "feat(api-admin): add ReportController (queue/submissions/hide/request-explanation/decide)"
```

---

### Task 15: backend — 전체 컴파일 + 기존 테스트 회귀 확인

**Files:** 없음(검증 전용)

- [ ] **Step 1: 전체 모듈 컴파일**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (persistence/api-admin/api-public/scheduler/app 등 전 모듈)

- [ ] **Step 2: 기존 순수 함수 테스트 회귀 확인**

Run: `cd backend && ./gradlew :domain-core:test :merge:test :audit:test`
Expected: BUILD SUCCESSFUL — 이번 작업은 이 모듈들을 건드리지 않으므로 회귀가 없어야 한다.

- [ ] **Step 3 (실패 시): 실패한 태스크로 돌아가 수정**

---

### Task 16: api-spec — `openapi.yaml`에 `/v1/reports*` · `/admin/reports*` 전체 반영

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

- [ ] **Step 1: 태그 추가 — `app-me` 태그 다음에 삽입**

기존:

```yaml
  - name: app-me
    description: 앱 · 등급/원장/설정/워치
  - name: admin-queues
```

교체:

```yaml
  - name: app-me
    description: 앱 · 등급/원장/설정/워치
  - name: app-reports
    description: 앱 · 신고/소명
  - name: admin-queues
```

- [ ] **Step 2: `/v1/reports*` 경로 추가 — `/v1/trends/{id}/endorse` 블록 다음, "앱 · 나" 섹션 앞에 삽입**

기존:

```yaml
  /v1/trends/{id}/endorse:
    post:
      tags: [app-submissions]
      summary: 동의 (중복 제보를 endorsement로 전환)
      description: 제보권 미소모. 적중 시 소액 가점, 실패 무감점. 유저 단위 dedup.
      parameters: [{ $ref: '#/components/parameters/TrendId' }]
      responses:
        '201': { description: 동의 처리됨 }
        '409': { description: 이미 동의/제보한 유저 }

  # ─────────────────────────────  앱 · 나  ─────────────────────────────
```

교체:

```yaml
  /v1/trends/{id}/endorse:
    post:
      tags: [app-submissions]
      summary: 동의 (중복 제보를 endorsement로 전환)
      description: 제보권 미소모. 적중 시 소액 가점, 실패 무감점. 유저 단위 dedup.
      parameters: [{ $ref: '#/components/parameters/TrendId' }]
      responses:
        '201': { description: 동의 처리됨 }
        '409': { description: 이미 동의/제보한 유저 }

  # ─────────────────────────────  앱 · 신고  ─────────────────────────────
  /v1/reports:
    post:
      tags: [app-reports]
      summary: 신고 접수 (대상은 TrendItem — 개별 제보 원문은 유저에게 노출되지 않음)
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ReportCreate' }
      responses:
        '201':
          description: 접수됨
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ReportMine' }
  /v1/reports/me:
    get:
      tags: [app-reports]
      summary: 내가 접수한 신고 목록 + 처리 결과
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/ReportMine' } }
  /v1/reports/received:
    get:
      tags: [app-reports]
      summary: 나를 지목해 소명을 요구한 신고 목록 (로그인 유저 = 지목된 submission의 작성자)
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/ReportReceived' } }
  /v1/reports/{id}/explanation:
    post:
      tags: [app-reports]
      summary: 소명 제출
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [text]
              properties:
                text: { type: string, maxLength: 2000 }
      responses:
        '200': { description: 제출됨 }
        '403': { description: 소명 대상으로 지목되지 않음 }
        '409': { description: 소명을 제출할 수 있는 상태(EXPLAINING)가 아님 }

  # ─────────────────────────────  앱 · 나  ─────────────────────────────
```

- [ ] **Step 3: `/admin/reports*` 경로 전체 교체 — 기존 2개 경로(큐 조회·decide)를 5개로 확장**

기존:

```yaml
  /admin/reports:
    get:
      tags: [admin-queues]
      summary: 신고 콘텐츠 큐 (ADM-410 · 신고자 비식별)
      security: [{ cookieAuth: [] }]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Report' } }
  /admin/reports/{id}/decide:
    post:
      tags: [admin-queues]
      summary: 신고 결정 (복원 / 영구비공개 / 수정후복원)
      security: [{ cookieAuth: [] }]
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [decision]
              properties:
                decision: { type: string, enum: [RESTORE, HIDE_PERMANENT, EDIT_RESTORE] }
                note: { type: string }
      responses:
        '200': { description: 처리됨 }
```

교체:

```yaml
  /admin/reports:
    get:
      tags: [admin-queues]
      summary: 신고 콘텐츠 큐 (ADM-410 · OPEN/EXPLAINING만 · 신고자 비식별)
      security: [{ cookieAuth: [] }]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Report' } }
  /admin/reports/{id}/submissions:
    get:
      tags: [admin-queues]
      summary: 해당 트렌드의 제보 원문 목록 — 관리자가 소명 대상을 지목할 후보(자동 추정 없음)
      security: [{ cookieAuth: [] }]
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/ReportSubmissionCandidate' } }
  /admin/reports/{id}/hide:
    post:
      tags: [admin-queues]
      summary: 즉시 비공개 + 소명 요청 (1차 처리)
      security: [{ cookieAuth: [] }]
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [submissionId]
              properties:
                submissionId: { type: string, format: uuid, description: 관리자가 지목한 문제 제보 }
                note: { type: string }
      responses:
        '200':
          description: 처리됨
          content: { application/json: { schema: { $ref: '#/components/schemas/Report' } } }
        '422': { description: submissionId 누락 또는 해당 트렌드에 속하지 않는 제보 }
  /admin/reports/{id}/request-explanation:
    post:
      tags: [admin-queues]
      summary: 공개 유지한 채 소명 요청 (1차 처리)
      security: [{ cookieAuth: [] }]
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [submissionId]
              properties:
                submissionId: { type: string, format: uuid }
                note: { type: string }
      responses:
        '200':
          description: 처리됨
          content: { application/json: { schema: { $ref: '#/components/schemas/Report' } } }
        '422': { description: submissionId 누락 또는 해당 트렌드에 속하지 않는 제보 }
  /admin/reports/{id}/decide:
    post:
      tags: [admin-queues]
      summary: 신고 최종 결정 (복원 / 영구비공개 / 수정후복원) — OPERATOR 이상
      security: [{ cookieAuth: [] }]
      parameters: [{ name: id, in: path, required: true, schema: { type: string, format: uuid } }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [decision]
              properties:
                decision: { type: string, enum: [RESTORE, HIDE_PERMANENT, EDIT_RESTORE] }
                note: { type: string }
                newCanonicalName: { type: string, description: EDIT_RESTORE일 때 필수 }
      responses:
        '200':
          description: 처리됨
          content: { application/json: { schema: { $ref: '#/components/schemas/Report' } } }
        '403': { description: OPERATOR 미만 }
        '422': { description: EXPLAINING 상태가 아니거나 EDIT_RESTORE인데 newCanonicalName 누락 }
```

- [ ] **Step 4: `Report` 스키마 보강 + 신규 스키마 3종 추가**

기존:

```yaml
    Report:
      type: object
      properties:
        id: { type: string, format: uuid }
        targetType: { type: string }
        targetId: { type: string, format: uuid }
        status: { type: string }
        createdAt: { type: string, format: date-time }
```

교체:

```yaml
    Report:
      type: object
      description: 신고자(reporterId)는 관리자 응답에 절대 포함하지 않는다(비식별 원칙).
      properties:
        id: { type: string, format: uuid }
        trendItemId: { type: string, format: uuid }
        reason: { type: string, enum: [DEFAMATION, BUSINESS_INTERFERENCE, OTHER] }
        detail: { type: [string, 'null'] }
        status: { type: string, enum: [OPEN, EXPLAINING, DECIDED] }
        submissionId: { type: [string, 'null'], format: uuid, description: 1차 처리 전에는 null }
        explanationDeadline: { type: [string, 'null'] }
        explanationText: { type: [string, 'null'] }
        decision: { type: [string, 'null'], enum: [RESTORE, HIDE_PERMANENT, EDIT_RESTORE, null] }
        decisionNote: { type: [string, 'null'] }
        createdAt: { type: string }
    ReportSubmissionCandidate:
      type: object
      description: 관리자가 소명 대상을 지목할 때 보는 원문 목록(병합검수 화면과 동일 뷰).
      properties:
        submissionId: { type: string, format: uuid }
        handle: { type: string }
        rawInput: { type: string }
        oneLine: { type: string }
        evidenceUrl: { type: string }
        createdAt: { type: string }
    ReportCreate:
      type: object
      required: [trendItemId, reason]
      properties:
        trendItemId: { type: string, format: uuid }
        reason: { type: string, enum: [DEFAMATION, BUSINESS_INTERFERENCE, OTHER] }
        detail: { type: string, maxLength: 1000 }
    ReportMine:
      type: object
      properties:
        id: { type: string, format: uuid }
        trendItemId: { type: string, format: uuid }
        status: { type: string, enum: [OPEN, EXPLAINING, DECIDED] }
        reason: { type: string, enum: [DEFAMATION, BUSINESS_INTERFERENCE, OTHER] }
        decision: { type: [string, 'null'] }
        decisionNote: { type: [string, 'null'] }
        createdAt: { type: string, format: date-time }
        decidedAt: { type: [string, 'null'], format: date-time }
    ReportReceived:
      type: object
      properties:
        id: { type: string, format: uuid }
        trendItemId: { type: string, format: uuid }
        reason: { type: string, enum: [DEFAMATION, BUSINESS_INTERFERENCE, OTHER] }
        detail: { type: [string, 'null'] }
        explanationDeadline: { type: [string, 'null'], format: date-time }
        explanationSubmitted: { type: boolean }
        status: { type: string, enum: [OPEN, EXPLAINING, DECIDED] }
```

- [ ] **Step 5: 커밋**

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api-spec): add /v1/reports* and expand /admin/reports* endpoints"
```

---

### Task 17: admin frontend — 신고 큐 타입 추가

**Files:**
- Modify: `admin/src/api/types.ts`

- [ ] **Step 1: 파일 끝에 추가**

```typescript
export interface ReportSubmissionCandidate {
  submissionId: string;
  handle: string;
  rawInput: string;
  oneLine: string;
  evidenceUrl: string;
  createdAt: string;
}
export interface ReportQueueItem {
  id: string;
  trendItemId: string;
  reason: "DEFAMATION" | "BUSINESS_INTERFERENCE" | "OTHER";
  detail: string | null;
  status: "OPEN" | "EXPLAINING" | "DECIDED";
  submissionId: string | null;
  explanationDeadline: string | null;
  explanationText: string | null;
  decision: "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE" | null;
  decisionNote: string | null;
  createdAt: string;
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/api/types.ts
git commit -m "feat(admin): add ReportQueueItem/ReportSubmissionCandidate types"
```

---

### Task 18: admin frontend — API 훅 추가

**Files:**
- Modify: `admin/src/api/hooks.ts`

- [ ] **Step 1: import에 타입 추가**

기존:

```typescript
import type { AdminAccountSummary, ApprovalRequestView, AdminUserDetail, AuditEntry, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, SeedAccuracyRow, SeedSubmissionRequest, SeedSubmissionResult, TrendItemSummary, TrendItemDetail, VerdictListResponse } from "./types";
```

교체:

```typescript
import type { AdminAccountSummary, ApprovalRequestView, AdminUserDetail, AuditEntry, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, ReportQueueItem, ReportSubmissionCandidate, SeedAccuracyRow, SeedSubmissionRequest, SeedSubmissionResult, TrendItemSummary, TrendItemDetail, VerdictListResponse } from "./types";
```

- [ ] **Step 2: 파일 끝에 훅 추가**

```typescript
export const useReportQueue = () =>
  useData<ReportQueueItem[]>(["admin", "reports"], "/admin/reports", fx.fxReportQueue);

export const fetchReportSubmissionCandidates = (reportId: string) =>
  api.get<ReportSubmissionCandidate[]>(`/admin/reports/${reportId}/submissions`);

export const hideReport = (reportId: string, submissionId: string, note?: string) =>
  api.post<ReportQueueItem>(`/admin/reports/${reportId}/hide`, { submissionId, note });

export const requestExplanation = (reportId: string, submissionId: string, note?: string) =>
  api.post<ReportQueueItem>(`/admin/reports/${reportId}/request-explanation`, { submissionId, note });

export const decideReport = (reportId: string, decision: "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE", note: string, newCanonicalName?: string) =>
  api.post<ReportQueueItem>(`/admin/reports/${reportId}/decide`, { decision, note, newCanonicalName });
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/api/hooks.ts
git commit -m "feat(admin): add report queue hooks (useReportQueue/hide/requestExplanation/decide)"
```

---

### Task 19: admin frontend — `fxReportQueue` 픽스처

**Files:**
- Modify: `admin/src/fixtures.ts`

- [ ] **Step 1: import에 `ReportQueueItem` 추가**

기존:

```typescript
import type { AdminAccountSummary, ApprovalRequestView, AdminUserDetail, AuditEntry, ImminentItem, JudgedItem, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, SeedAccuracyRow, SimulationSummary, TrendItemDetail, TrendItemSummary, VerdictListResponse } from "./api/types";
```

교체:

```typescript
import type { AdminAccountSummary, ApprovalRequestView, AdminUserDetail, AuditEntry, ImminentItem, JudgedItem, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, ReportQueueItem, SeedAccuracyRow, SimulationSummary, TrendItemDetail, TrendItemSummary, VerdictListResponse } from "./api/types";
```

- [ ] **Step 2: `fxApprovals` 바로 다음에 추가**

```typescript
export const fxReportQueue: ReportQueueItem[] = [
  {
    id: "rp-1", trendItemId: "ti-1", reason: "DEFAMATION", detail: "특정 인물을 비하하는 표현이 포함돼 있습니다",
    status: "OPEN", submissionId: null, explanationDeadline: null, explanationText: null,
    decision: null, decisionNote: null, createdAt: "2026-08-19 14:20",
  },
  {
    id: "rp-2", trendItemId: "ti-3", reason: "BUSINESS_INTERFERENCE", detail: "경쟁사 비방성 제보로 의심됩니다",
    status: "EXPLAINING", submissionId: "sub-1", explanationDeadline: "2026-08-21 09:12", explanationText: null,
    decision: null, decisionNote: null, createdAt: "2026-08-18 09:00",
  },
];
```

- [ ] **Step 3: 커밋**

```bash
git add admin/src/fixtures.ts
git commit -m "feat(admin): add fxReportQueue fixture"
```

---

### Task 20: admin frontend — `CAN.reportTriage`/`CAN.reportDecide` 권한 추가

**Files:**
- Modify: `admin/src/state/role.tsx`

- [ ] **Step 1: `CAN` 객체에 항목 추가**

`approvalConfirm: (r: Role) => r === "ADMIN",` 다음 줄에 추가:

```typescript
  reportTriage: (r: Role) => r === "REVIEWER" || r === "OPERATOR" || r === "ADMIN",
  reportDecide: (r: Role) => r === "OPERATOR" || r === "ADMIN",
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/state/role.tsx
git commit -m "feat(admin): add CAN.reportTriage/reportDecide capabilities"
```

---

### Task 21: admin frontend — `ReportQueueScreen.tsx` 신규

**Files:**
- Create: `admin/src/screens/ReportQueueScreen.tsx`

`MergeQueueScreen.tsx`의 확장 카드 패턴을 따른다.

- [ ] **Step 1: 파일 작성**

```tsx
import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useReportQueue, fetchReportSubmissionCandidates, hideReport, requestExplanation, decideReport } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import type { ReportQueueItem, ReportSubmissionCandidate } from "../api/types";

const REASON_LABEL: Record<string, string> = {
  DEFAMATION: "명예훼손", BUSINESS_INTERFERENCE: "영업방해", OTHER: "기타",
};
const STATUS_LABEL: Record<string, string> = {
  OPEN: "미처리", EXPLAINING: "소명 대기", DECIDED: "처리 완료",
};
const DECISIONS: { key: "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE"; label: string }[] = [
  { key: "RESTORE", label: "복원" },
  { key: "HIDE_PERMANENT", label: "영구비공개" },
  { key: "EDIT_RESTORE", label: "수정 후 복원" },
];

export default function ReportQueueScreen() {
  const q = useReportQueue();
  return (
    <div style={{ maxWidth: 900 }}>
      <div style={{ marginBottom: 16, font: "500 12px Pretendard", color: C.sub }}>
        4시간 자동 임시비공개 없음 — 사람이 판단하기 전까지 절대 비공개되지 않습니다.
      </div>
      <StateView query={q}>
        {(list) => list.length === 0 ? (
          <Card><div style={{ textAlign: "center", padding: 40 }}><b>큐를 비웠습니다</b></div></Card>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            {list.map((r) => <ReportCard key={r.id} report={r} />)}
          </div>
        )}
      </StateView>
    </div>
  );
}

function ReportCard({ report }: { report: ReportQueueItem }) {
  const { role } = useRole();
  const qc = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [candidates, setCandidates] = useState<ReportSubmissionCandidate[] | null>(null);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [selected, setSelected] = useState<string | null>(report.submissionId);
  const [note, setNote] = useState("");
  const [decision, setDecision] = useState<"RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE">("RESTORE");
  const [newName, setNewName] = useState("");
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const toggle = async () => {
    const opening = !expanded;
    setExpanded(opening);
    if (opening && !candidates) {
      setLoadingCandidates(true);
      try {
        setCandidates(USE_FIXTURES ? [] : await fetchReportSubmissionCandidates(report.id));
      } catch (e) {
        flash(e instanceof ApiError ? e.message : "제보 원문을 불러오지 못했습니다");
      } finally {
        setLoadingCandidates(false);
      }
    }
  };

  const refresh = () => qc.invalidateQueries({ queryKey: ["admin", "reports"] });

  const triage = async (action: "hide" | "request-explanation") => {
    if (!selected) { flash("소명 대상 제보를 먼저 지목하세요"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${action === "hide" ? "즉시비공개" : "소명요청"} 처리됨`); return; }
    setBusy(true);
    try {
      if (action === "hide") await hideReport(report.id, selected, note);
      else await requestExplanation(report.id, selected, note);
      await refresh();
      flash("처리됐습니다 (감사 로그 기록)");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "처리에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  const decide = async () => {
    if (decision === "EDIT_RESTORE" && !newName.trim()) { flash("수정 후 복원은 새 대표명이 필요합니다"); return; }
    if (!note.trim()) { flash("결정 사유는 필수입니다"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${decision} 결정됨`); return; }
    setBusy(true);
    try {
      await decideReport(report.id, decision, note, decision === "EDIT_RESTORE" ? newName : undefined);
      await refresh();
      flash("결정이 확정됐습니다 (감사 로그 기록)");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "결정에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card style={{ padding: 0, overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <b style={{ fontSize: 13 }}>{REASON_LABEL[report.reason] ?? report.reason}</b>
          <span style={{ font: "600 10.5px ui-monospace, monospace", padding: "4px 8px", borderRadius: 6, background: "rgba(20,19,15,0.06)", color: C.sub }}>
            {STATUS_LABEL[report.status] ?? report.status}
          </span>
          {report.explanationDeadline && (
            <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>소명 기한 {report.explanationDeadline}</span>
          )}
        </div>
        <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>접수 {report.createdAt}</span>
      </div>

      <div style={{ padding: "16px 20px" }}>
        {report.detail && <div style={{ font: "500 12.5px Pretendard", color: C.sub }}>{report.detail}</div>}

        <button onClick={toggle} style={{ marginTop: 12, background: "none", border: "none", padding: 0, cursor: "pointer", font: "600 11.5px Pretendard", color: C.sub, textDecoration: "underline" }}>
          {expanded ? "접기" : "제보 원문 보기 (소명 대상 지목)"}
        </button>

        {expanded && (
          <div style={{ marginTop: 12 }}>
            {loadingCandidates && <div style={{ color: C.faint, font: "500 12px Pretendard" }}>불러오는 중…</div>}
            {candidates?.map((c) => (
              <label key={c.submissionId} style={{ display: "flex", gap: 10, alignItems: "flex-start", padding: "10px 12px", borderRadius: 9, background: selected === c.submissionId ? "rgba(20,19,15,0.06)" : "rgba(20,19,15,0.03)", marginBottom: 8, cursor: "pointer" }}>
                <input type="radio" name={`report-${report.id}`} checked={selected === c.submissionId}
                  onChange={() => setSelected(c.submissionId)} style={{ marginTop: 3 }} disabled={report.status !== "OPEN"} />
                <div style={{ flex: 1 }}>
                  <div style={{ display: "flex", justifyContent: "space-between" }}>
                    <span style={{ font: "600 11.5px Pretendard" }}>{c.handle}</span>
                    <span style={{ font: "500 10.5px ui-monospace, monospace", color: C.faint }}>{c.createdAt}</span>
                  </div>
                  <div style={{ font: "600 12.5px Pretendard", marginTop: 4 }}>{c.rawInput}</div>
                  <div style={{ font: "400 11.5px Pretendard", color: C.sub, marginTop: 3 }}>{c.oneLine}</div>
                  {c.evidenceUrl && (
                    <a href={c.evidenceUrl} target="_blank" rel="noreferrer" style={{ font: "500 11px Pretendard", color: C.peak, marginTop: 4, display: "inline-block" }}>근거 링크 열기 ↗</a>
                  )}
                </div>
              </label>
            ))}
          </div>
        )}

        {report.explanationText && (
          <div style={{ marginTop: 14, padding: "11px 13px", borderRadius: 10, background: "rgba(20,19,15,0.035)" }}>
            <Label>제출된 소명</Label>
            <div style={{ font: "500 12.5px Pretendard" }}>{report.explanationText}</div>
          </div>
        )}
      </div>

      {report.status === "OPEN" && (
        <div style={{ padding: "16px 20px", borderTop: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="처리 근거 (선택 · 감사 로그에 기록됩니다)"
            style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <Btn tone="danger" disabled={!CAN.reportTriage(role) || busy} onClick={() => triage("hide")}>즉시비공개 + 소명요청</Btn>
            <Btn disabled={!CAN.reportTriage(role) || busy} onClick={() => triage("request-explanation")}>공개 유지 + 소명요청</Btn>
          </div>
          {!CAN.reportTriage(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 처리 권한이 없습니다. REVIEWER 이상 필요.</div>}
        </div>
      )}

      {report.status === "EXPLAINING" && (
        <div style={{ padding: "16px 20px", borderTop: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <Label>최종 결정</Label>
          <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
            {DECISIONS.map((d) => (
              <button key={d.key} onClick={() => setDecision(d.key)}
                style={{ padding: "8px 12px", borderRadius: 8, border: decision === d.key ? `1px solid ${C.ink}` : "1px solid rgba(20,19,15,0.14)", background: decision === d.key ? C.ink : "#fff", color: decision === d.key ? "#fff" : C.ink, cursor: "pointer", font: "600 12px Pretendard" }}>
                {d.label}
              </button>
            ))}
          </div>
          {decision === "EDIT_RESTORE" && (
            <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="새 대표명"
              style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard", marginBottom: 10 }} />
          )}
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="결정 사유 (필수 · 감사 로그에 기록됩니다)"
            style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <Btn tone="primary" disabled={!CAN.reportDecide(role) || busy} onClick={decide}>결정 확정</Btn>
          </div>
          {!CAN.reportDecide(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 결정 권한이 없습니다. OPERATOR 이상 필요.</div>}
        </div>
      )}

      {toast && (
        <div style={{ padding: "10px 20px", borderTop: `1px solid ${C.line}`, font: "500 12px Pretendard", color: C.sub }}>{toast}</div>
      )}
    </Card>
  );
}
```

- [ ] **Step 2: 커밋**

```bash
git add admin/src/screens/ReportQueueScreen.tsx
git commit -m "feat(admin): add ReportQueueScreen (ADM-410 신고 콘텐츠 큐)"
```

---

### Task 22: admin frontend — 네비게이션·라우팅 연결

**Files:**
- Modify: `admin/src/components/Layout.tsx`
- Modify: `admin/src/App.tsx`

- [ ] **Step 1: `Layout.tsx` — `ScreenId`에 `"ADM-410"` 추가**

기존:

```typescript
export type ScreenId = "ADM-010" | "ADM-100" | "ADM-110" | "ADM-111" | "ADM-200" | "ADM-500" | "ADM-600" | "ADM-620" | "ADM-311" | "ADM-700" | "ADM-800" | "stub";
```

교체:

```typescript
export type ScreenId = "ADM-010" | "ADM-100" | "ADM-110" | "ADM-111" | "ADM-200" | "ADM-410" | "ADM-500" | "ADM-600" | "ADM-620" | "ADM-311" | "ADM-700" | "ADM-800" | "stub";
```

- [ ] **Step 2: `Layout.tsx` — `NAV`의 "큐" 그룹에서 신고 콘텐츠 스텁을 실 화면으로 교체**

기존:

```typescript
    { id: "stub", label: "신고 콘텐츠", code: "ADM-410", badge: 2 },
```

교체:

```typescript
    { id: "ADM-410", label: "신고 콘텐츠", code: "ADM-410", badge: 2 },
```

- [ ] **Step 3: `App.tsx` — import·TITLE·라우팅 추가**

`import ApprovalsScreen from "./screens/ApprovalsScreen";` 다음 줄에 추가:

```typescript
import ReportQueueScreen from "./screens/ReportQueueScreen";
```

`TITLE` 맵의 `"ADM-620": "승인 대기함",` 다음 줄에 추가:

```typescript
  "ADM-410": "신고 콘텐츠",
```

`{screen === "ADM-620" && <ApprovalsScreen />}` 다음 줄에 추가:

```tsx
      {screen === "ADM-410" && <ReportQueueScreen />}
```

- [ ] **Step 4: 커밋**

```bash
git add admin/src/components/Layout.tsx admin/src/App.tsx
git commit -m "feat(admin): wire ADM-410 report queue screen into nav and routing"
```

---

### Task 23: admin frontend — typecheck 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: typecheck 실행**

Run: `cd admin && npm run typecheck`
Expected: 에러 없음.

- [ ] **Step 2 (에러 발견 시): 수정 후 재검증**

---

### Task 24: app frontend — 신고 API 타입 추가

**Files:**
- Modify: `app/src/api/types.ts`

- [ ] **Step 1: 파일 끝에 추가**

```typescript
export type ReportReason = "DEFAMATION" | "BUSINESS_INTERFERENCE" | "OTHER";
export type ReportStatus = "OPEN" | "EXPLAINING" | "DECIDED";
export type ReportDecisionType = "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE";

export interface ReportCreate {
  trendItemId: string;
  reason: ReportReason;
  detail?: string;
}
export interface ReportMine {
  id: string;
  trendItemId: string;
  status: ReportStatus;
  reason: ReportReason;
  decision: ReportDecisionType | null;
  decisionNote: string | null;
  createdAt: string;
  decidedAt: string | null;
}
export interface ReportReceived {
  id: string;
  trendItemId: string;
  reason: ReportReason;
  detail: string | null;
  explanationDeadline: string | null;
  explanationSubmitted: boolean;
  status: ReportStatus;
}
```

- [ ] **Step 2: 커밋**

```bash
git add app/src/api/types.ts
git commit -m "feat(app): add report types (ReportCreate/ReportMine/ReportReceived)"
```

---

### Task 25: app frontend — 신고 API 훅 추가

**Files:**
- Modify: `app/src/api/hooks.ts`

- [ ] **Step 1: import에 타입 추가**

기존:

```typescript
import type {
  GradeStatus, Ledger, MeSummary, Preferences, SubmissionCreate, SubmissionMine,
  TrendDetail, TrendList, VoteResult, WatchItem,
} from "./types";
```

교체:

```typescript
import type {
  GradeStatus, Ledger, MeSummary, Preferences, ReportCreate, ReportMine, ReportReceived, SubmissionCreate, SubmissionMine,
  TrendDetail, TrendList, VoteResult, WatchItem,
} from "./types";
```

- [ ] **Step 2: `qk` 객체에 키 추가**

기존:

```typescript
  watch: ["me", "watch"] as const,
  prefs: ["me", "preferences"] as const,
};
```

교체:

```typescript
  watch: ["me", "watch"] as const,
  prefs: ["me", "preferences"] as const,
  myReports: ["reports", "me"] as const,
  receivedReports: ["reports", "received"] as const,
};
```

- [ ] **Step 3: 파일 끝에 훅 추가**

```typescript
export const useMyReports = () =>
  useQuery({ queryKey: qk.myReports, queryFn: () => api.get<ReportMine[]>("/v1/reports/me") });

export const useReceivedReports = () =>
  useQuery({ queryKey: qk.receivedReports, queryFn: () => api.get<ReportReceived[]>("/v1/reports/received") });

export const useCreateReport = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ReportCreate) => api.post<ReportMine>("/v1/reports", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.myReports }),
  });
};

export const useSubmitExplanation = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, text }: { id: string; text: string }) => api.post<void>(`/v1/reports/${id}/explanation`, { text }),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.receivedReports }),
  });
};
```

- [ ] **Step 4: 커밋**

```bash
git add app/src/api/hooks.ts
git commit -m "feat(app): add report hooks (useMyReports/useReceivedReports/useCreateReport/useSubmitExplanation)"
```

---

### Task 26: app frontend — `DetailScreen.tsx`에 "신고하기" 섹션 추가

**Files:**
- Modify: `app/src/screens/DetailScreen.tsx`

- [ ] **Step 1: import 수정**

기존:

```tsx
import React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useRoute, type RouteProp } from "@react-navigation/native";
import { useTrendDetail, useToggleWatch, useVote } from "../api/hooks";
import { Card, Muted, Screen, StageChip, StateView } from "../components/ui";
import { C, STAGE_COLOR } from "../theme";
import type { HomeStackParamList } from "../navigation/types";
```

교체:

```tsx
import React, { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useRoute, type RouteProp } from "@react-navigation/native";
import { useCreateReport, useTrendDetail, useToggleWatch, useVote } from "../api/hooks";
import type { ReportReason } from "../api/types";
import { Card, Muted, Screen, StageChip, StateView } from "../components/ui";
import { C, STAGE_COLOR } from "../theme";
import type { HomeStackParamList } from "../navigation/types";
```

- [ ] **Step 2: 워치 에러 블록 다음에 신고 섹션 삽입**

기존:

```tsx
              {toggleWatch.isError && (
                <Text style={s.watchError}>
                  {toggleWatch.error instanceof Error ? toggleWatch.error.message : "요청에 실패했습니다"}
                </Text>
              )}
            </>
          );
        }}
      </StateView>
```

교체:

```tsx
              {toggleWatch.isError && (
                <Text style={s.watchError}>
                  {toggleWatch.error instanceof Error ? toggleWatch.error.message : "요청에 실패했습니다"}
                </Text>
              )}

              {/* 신고 */}
              <ReportSection trendId={params.id} />
            </>
          );
        }}
      </StateView>
```

- [ ] **Step 3: `VoteBtn` 함수 다음에 `ReportSection` 컴포넌트 추가**

기존:

```tsx
function VoteBtn({ label, onPress, busy }: { label: string; onPress: () => void; busy: boolean }) {
  return (
    <Pressable onPress={onPress} disabled={busy} style={s.voteBtn}>
      <Text style={s.voteBtnText}>{label}</Text>
    </Pressable>
  );
}
```

교체:

```tsx
function VoteBtn({ label, onPress, busy }: { label: string; onPress: () => void; busy: boolean }) {
  return (
    <Pressable onPress={onPress} disabled={busy} style={s.voteBtn}>
      <Text style={s.voteBtnText}>{label}</Text>
    </Pressable>
  );
}

const REPORT_REASONS: { key: ReportReason; label: string }[] = [
  { key: "DEFAMATION", label: "명예훼손" },
  { key: "BUSINESS_INTERFERENCE", label: "영업방해" },
  { key: "OTHER", label: "기타" },
];

function ReportSection({ trendId }: { trendId: string }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [detail, setDetail] = useState("");
  const report = useCreateReport();

  if (report.isSuccess) {
    return (
      <View style={{ marginTop: 22 }}>
        <Muted style={{ color: C.rising }}>신고가 접수됐습니다. 검토 후 처리됩니다.</Muted>
      </View>
    );
  }

  const submit = () => {
    if (!reason) return;
    report.mutate({ trendItemId: trendId, reason, detail: detail.trim() || undefined });
  };

  return (
    <View style={{ marginTop: 22 }}>
      <Pressable onPress={() => setOpen((o) => !o)}>
        <Text style={s.reportToggle}>{open ? "신고 취소" : "신고하기"}</Text>
      </Pressable>
      {open && (
        <Card style={{ marginTop: 10 }}>
          <Text style={s.sectionLabel}>신고 사유</Text>
          <View style={{ flexDirection: "row", gap: 6, marginTop: 8, flexWrap: "wrap" }}>
            {REPORT_REASONS.map((r) => {
              const on = reason === r.key;
              return (
                <Pressable key={r.key} onPress={() => setReason(r.key)} style={[s.reasonChip, on && { backgroundColor: C.ink, borderColor: C.ink }]}>
                  <Text style={{ color: on ? "#fff" : C.ink, fontWeight: "500", fontSize: 12.5 }}>{r.label}</Text>
                </Pressable>
              );
            })}
          </View>
          <TextInput value={detail} onChangeText={setDetail} placeholder="상세 내용 (선택)" placeholderTextColor="rgba(20,19,15,0.35)"
            style={[s.reportInput, { marginTop: 10 }]} multiline />
          {report.isError && <Muted style={{ color: C.fading, marginTop: 8 }}>{report.error instanceof Error ? report.error.message : "신고 접수에 실패했습니다"}</Muted>}
          <Pressable onPress={submit} disabled={!reason || report.isPending}
            style={[s.reportSubmit, { backgroundColor: reason ? C.fading : "rgba(20,19,15,0.1)" }]}>
            <Text style={{ color: reason ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 13.5 }}>
              {report.isPending ? "접수 중…" : "신고 접수"}
            </Text>
          </Pressable>
        </Card>
      )}
    </View>
  );
}
```

- [ ] **Step 4: 스타일 추가**

기존:

```tsx
  watchError: { marginTop: 10, fontSize: 13, color: C.fading, textAlign: "center", lineHeight: 20 },
});
```

교체:

```tsx
  watchError: { marginTop: 10, fontSize: 13, color: C.fading, textAlign: "center", lineHeight: 20 },
  reportToggle: { fontSize: 13, fontWeight: "600", color: C.sub },
  reasonChip: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 100, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  reportInput: { backgroundColor: "#fff", borderWidth: 1, borderColor: "rgba(20,19,15,0.1)", borderRadius: 12, paddingHorizontal: 13, paddingVertical: 12, fontSize: 14, color: C.ink, minHeight: 60, textAlignVertical: "top" },
  reportSubmit: { marginTop: 12, paddingVertical: 13, borderRadius: 12, alignItems: "center" },
});
```

- [ ] **Step 5: 커밋**

```bash
git add app/src/screens/DetailScreen.tsx
git commit -m "feat(app): add report section to DetailScreen"
```

---

### Task 27: app frontend — `MeScreen.tsx`에 "받은 소명요청"/"내가 접수한 신고" 섹션 추가

**Files:**
- Modify: `app/src/screens/MeScreen.tsx`

- [ ] **Step 1: import 수정**

기존:

```tsx
import React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useMeSummary, useMyGrade, useMyLedger, useMySubmissions, useWatch } from "../api/hooks";
import type { GradeRequirementKind } from "../api/types";
import { Card, H1, Muted, Screen, StateView } from "../components/ui";
import type { MeNav } from "../navigation/types";
import { useAuth } from "../state/auth";
import { C } from "../theme";
```

교체:

```tsx
import React, { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useMeSummary, useMyGrade, useMyLedger, useMyReports, useMySubmissions, useReceivedReports, useSubmitExplanation, useWatch } from "../api/hooks";
import type { GradeRequirementKind, ReportReceived } from "../api/types";
import { Card, H1, Muted, Screen, StateView } from "../components/ui";
import type { MeNav } from "../navigation/types";
import { useAuth } from "../state/auth";
import { C } from "../theme";
```

- [ ] **Step 2: 훅 호출 추가**

기존:

```tsx
  const watch = useWatch();
  const mySubs = useMySubmissions();
```

교체:

```tsx
  const watch = useWatch();
  const mySubs = useMySubmissions();
  const myReports = useMyReports();
  const receivedReports = useReceivedReports();
```

- [ ] **Step 3: "원장" 섹션 다음, 설정 진입 버튼 앞에 두 섹션 삽입**

기존:

```tsx
      </View>

      <Pressable onPress={() => nav.navigate("Settings")} style={s.settingsRow}>
```

교체:

```tsx
      </View>

      {/* 받은 소명요청 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={receivedReports} empty={(d) => d.length === 0}>
          {(items) => (
            <Card>
              <Text style={s.sectionTitle}>받은 소명요청</Text>
              <View style={{ gap: 10, marginTop: 12 }}>
                {items.map((r) => <ReceivedRow key={r.id} report={r} />)}
              </View>
            </Card>
          )}
        </StateView>
      </View>

      {/* 내가 접수한 신고 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={myReports} empty={(d) => d.length === 0}>
          {(items) => (
            <Card>
              <Text style={s.sectionTitle}>내가 접수한 신고</Text>
              <View style={{ gap: 10, marginTop: 12 }}>
                {items.map((r) => (
                  <View key={r.id} style={{ flexDirection: "row", justifyContent: "space-between" }}>
                    <Text style={s.reqLabel}>{REASON_LABEL[r.reason]}</Text>
                    <Muted style={{ fontSize: 12 }}>{REPORT_STATUS_LABEL[r.status]}</Muted>
                  </View>
                ))}
              </View>
            </Card>
          )}
        </StateView>
      </View>

      <Pressable onPress={() => nav.navigate("Settings")} style={s.settingsRow}>
```

- [ ] **Step 4: `SubStat` 함수 다음에 `ReceivedRow` 컴포넌트 + 라벨 상수 추가**

기존:

```tsx
function SubStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <View>
      <Text style={{ fontSize: 20, fontWeight: "700", color }}>{value}</Text>
      <Muted style={{ fontSize: 11, marginTop: 2 }}>{label}</Muted>
    </View>
  );
}
```

교체:

```tsx
function SubStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <View>
      <Text style={{ fontSize: 20, fontWeight: "700", color }}>{value}</Text>
      <Muted style={{ fontSize: 11, marginTop: 2 }}>{label}</Muted>
    </View>
  );
}

const REASON_LABEL: Record<string, string> = { DEFAMATION: "명예훼손", BUSINESS_INTERFERENCE: "영업방해", OTHER: "기타" };
const REPORT_STATUS_LABEL: Record<string, string> = { OPEN: "접수됨", EXPLAINING: "소명 대기", DECIDED: "처리 완료" };

function ReceivedRow({ report }: { report: ReportReceived }) {
  const [text, setText] = useState("");
  const submit = useSubmitExplanation();
  const canSubmit = report.status === "EXPLAINING" && !report.explanationSubmitted;

  return (
    <View style={{ padding: 12, borderRadius: 10, backgroundColor: "rgba(20,19,15,0.03)" }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
        <Text style={{ fontSize: 13, fontWeight: "600", color: C.ink }}>{REASON_LABEL[report.reason]}</Text>
        <Muted style={{ fontSize: 11.5 }}>{REPORT_STATUS_LABEL[report.status]}</Muted>
      </View>
      {!!report.detail && <Muted style={{ fontSize: 12, marginTop: 4 }}>{report.detail}</Muted>}
      {report.explanationDeadline && <Muted style={{ fontSize: 11, marginTop: 4 }}>소명 기한: {report.explanationDeadline}</Muted>}
      {canSubmit && (
        <View style={{ marginTop: 8 }}>
          <TextInput value={text} onChangeText={setText} placeholder="소명 내용을 입력하세요" placeholderTextColor="rgba(20,19,15,0.35)"
            style={{ backgroundColor: "#fff", borderRadius: 10, borderWidth: 1, borderColor: "rgba(20,19,15,0.1)", padding: 10, fontSize: 13, color: C.ink, minHeight: 50, textAlignVertical: "top" }} multiline />
          <Pressable onPress={() => text.trim() && submit.mutate({ id: report.id, text: text.trim() })} disabled={!text.trim() || submit.isPending}
            style={{ marginTop: 8, alignSelf: "flex-start", paddingVertical: 8, paddingHorizontal: 14, borderRadius: 8, backgroundColor: text.trim() ? C.ink : "rgba(20,19,15,0.1)" }}>
            <Text style={{ color: text.trim() ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 12.5 }}>
              {submit.isPending ? "제출 중…" : "소명 제출"}
            </Text>
          </Pressable>
          {submit.isError && <Muted style={{ color: C.fading, fontSize: 11.5, marginTop: 6 }}>{submit.error instanceof Error ? submit.error.message : "제출에 실패했습니다"}</Muted>}
        </View>
      )}
      {report.explanationSubmitted && report.status === "EXPLAINING" && (
        <Muted style={{ fontSize: 11.5, marginTop: 6, color: C.rising }}>소명이 제출됐습니다. 결정을 기다리는 중입니다.</Muted>
      )}
    </View>
  );
}
```

- [ ] **Step 5: 커밋**

```bash
git add app/src/screens/MeScreen.tsx
git commit -m "feat(app): add received/my reports sections to MeScreen"
```

---

### Task 28: app frontend — typecheck 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: typecheck 실행**

Run: `cd app && npm run typecheck`
Expected: 에러 없음.

- [ ] **Step 2 (에러 발견 시): 수정 후 재검증**

---

### Task 29: end-to-end 검증

**Files:** 없음(검증 전용)

- [ ] **Step 1: 백엔드 기동**

로컬 dev DB(도커) 기동 상태에서 `cd backend && ./gradlew :app:bootRun`. Flyway가 V26을 적용하는지 로그로 확인.

- [ ] **Step 2: `curl`/`psql`로 신고 → 1차 처리 → 소명 → 결정 전체 플로우 확인**

1. 앱 유저 A(제보자, Firebase 토큰 보유)로 `POST /v1/submissions`를 호출해 트렌드 하나를 만든다. 응답의 `trendItemId`를 기록.
2. 앱 유저 B(신고자)로 `POST /v1/reports` (`{"trendItemId": "<위 id>", "reason": "DEFAMATION", "detail": "테스트 신고"}`) 호출 → `201` 확인, 응답의 `id`(신고 id) 기록.
3. 관리자(REVIEWER 이상, 세션 쿠키)로 `GET /admin/reports` 호출 → 방금 만든 신고가 `status: "OPEN"`으로 보이는지, **응답 JSON에 `reporterId`/`reporter` 관련 필드가 전혀 없는지** 확인(신고자 비식별 원칙).
4. `GET /admin/reports/{id}/submissions` 호출 → 유저 A의 제보가 후보로 보이는지 확인, `submissionId` 기록.
5. `POST /admin/reports/{id}/hide` (`{"submissionId": "<위 id>", "note": "테스트"}`) 호출 → `200`, 응답 `status: "EXPLAINING"` 확인.
6. `psql`로 `SELECT visibility FROM trend_items WHERE id = '<trendItemId>';` → `TEMP_HIDDEN` 확인.
7. `GET /v1/trends` / `GET /v1/trends/{trendItemId}`(비로그인 또는 다른 유저) 호출 → 목록에서 사라졌는지, 상세는 404인지 확인.
8. 유저 A(지목된 제보자)로 `GET /v1/reports/received` 호출 → 방금 신고가 보이는지 확인.
9. 유저 A로 `POST /v1/reports/{id}/explanation` (`{"text": "소명합니다"}`) 호출 → `200` 확인.
10. 유저 A가 아닌 다른 유저 C로 같은 explanation 호출 → `403` 확인.
11. 관리자(OPERATOR 이상)로 `POST /admin/reports/{id}/decide` (`{"decision": "RESTORE", "note": "소명 확인함"}`) 호출 → `200`, `status: "DECIDED"` 확인.
12. `psql`로 `trend_items.visibility`가 다시 `PUBLIC`으로 돌아왔는지, `admin_audit_log`에 `REPORT_HIDE`/`REPORT_DECIDE` 행이 쌓였는지 확인.
13. `psql`로 `SELECT count(*) FROM score_ledger WHERE ...`(해당 유저/트렌드 관련) — 신고 처리 전후로 원장 행 수가 전혀 변하지 않았는지 확인(순수 표시 계층 분리 검증, R2).

- [ ] **Step 3: 상태 검증 플로우 확인**

1. 새 신고를 하나 더 만들고, `submissionId` 없이 `POST /admin/reports/{id}/hide`(`{}`) 호출 → `422` 확인.
2. `EXPLAINING`이 아닌(`OPEN`) 신고에 대해 `POST /v1/reports/{id}/explanation` 호출 → `409` 확인.
3. `OPEN`이 아닌(`EXPLAINING`) 신고를 다시 `POST /admin/reports/{id}/hide` 호출 → `422` 확인(이미 1차 처리됨).
4. 세 번째 트렌드/신고를 만들고 `POST /admin/reports/{id}/request-explanation`(`{"submissionId": "<id>"}`)을 호출한 뒤, `psql`로 `SELECT visibility FROM trend_items WHERE id = '<trendItemId>';` → `PUBLIC` 그대로인지 확인(`hide`와 달리 공개 상태를 유지한 채 소명만 요구 — 설계서 §아키텍처의 핵심 차이).

- [ ] **Step 4: 프론트엔드 브라우저 확인**

`VITE_USE_FIXTURES=false`로 admin dev 서버 기동 후 ADM-410 화면 진입 → 큐 목록이 실 API에서 채워지는지, "제보 원문 보기" 확장 시 후보가 뜨는지, 역할(REVIEWER/OPERATOR)에 따라 버튼이 올바르게 활성화/비활성화되는지 확인. 이어서 앱(Expo)에서 트렌드 상세 화면의 "신고하기" 버튼과 "나" 탭의 "받은 소명요청"/"내가 접수한 신고" 섹션이 실제로 렌더되는지 확인.

- [ ] **Step 5 (문제 발견 시): 원인 파악 후 관련 태스크로 돌아가 수정, 재검증**
