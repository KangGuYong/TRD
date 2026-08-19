-- V23 · 전체 테이블·컬럼 주석
-- 스키마만 봐도 도메인을 알 수 있도록 모든 테이블/컬럼에 설명을 단다.
-- 내용은 CLAUDE.md 도메인 원칙(R1~R5)과 V1~V22 마이그레이션 주석을 기준으로 작성.

-- ════════════════════════════════════════════════════════════════════
-- users · 회원
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE users IS '앱/관리콘솔 공용 회원. 실명·연락처 등 PII는 여기 두지 않는다(별도 테이블 분리 예정).';
COMMENT ON COLUMN users.id IS 'PK.';
COMMENT ON COLUMN users.handle IS '화면에 노출되는 표시명(닉네임). 유니크.';
COMMENT ON COLUMN users.status IS '계정 상태(ACTIVE/SUSPENDED/DEACTIVATED).';
COMMENT ON COLUMN users.joined_at IS '가입 시각. 가입+7일 & 본인인증 전에는 제보 불가(도메인 규칙) 판단 기준.';
COMMENT ON COLUMN users.verified_at IS '본인인증 완료 시각. NULL이면 미인증.';
COMMENT ON COLUMN users.created_at IS '행 생성 시각.';
COMMENT ON COLUMN users.firebase_uid IS 'Firebase Authentication UID. 앱 로그인 주체 매핑(유니크, nullable).';

-- ════════════════════════════════════════════════════════════════════
-- trend_items · 트렌드 항목(병합된 클러스터) = 판정 단위
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE trend_items IS '병합된 트렌드 클러스터. 판정(HIT/MISS/VOID)이 이 단위로 내려진다.';
COMMENT ON COLUMN trend_items.id IS 'PK. 제보·판정·워치·완독 등 대부분의 테이블이 이 id를 참조한다.';
COMMENT ON COLUMN trend_items.canonical_name IS '화면에 표시되는 대표 명칭(최빈 표기). 판정 통보 후 변경은 앱 계층에서 차단.';
COMMENT ON COLUMN trend_items.normalized_key IS 'NFC 정규화 + 공백 정리 + 소문자화한 대표 키. 완전일치 병합·워치 매칭에 쓰임.';
COMMENT ON COLUMN trend_items.aliases IS '동일 항목의 다른 표기들(별칭 배열).';
COMMENT ON COLUMN trend_items.category IS '카테고리(MEME/PRODUCT/PERSON_CHANNEL/CHALLENGE/SLANG/ETC). 유저 개인화·오늘의5개 선정에 사용.';
COMMENT ON COLUMN trend_items.state IS '항목 상태(DRAFT/PENDING/JUDGING/RESOLVED/VOID/MERGED). PENDING·JUDGING이 관측 중, RESOLVED가 판정 완료.';
COMMENT ON COLUMN trend_items.first_seen_at IS '최초 제보 시각. 판정 기준선(baseline)의 시작점 — 값이 바뀌면(병합 등) baseline 재계산 필요.';
COMMENT ON COLUMN trend_items.embedding IS '벡터 임베딩. 임베딩 유사도 기반 자동/반자동 병합 판단에 사용(1024차원은 잠정값).';
COMMENT ON COLUMN trend_items.version IS '낙관적 락 카운터. 검수자 동시 병합 충돌 감지용.';
COMMENT ON COLUMN trend_items.merged_into IS '병합 패자 → 승자 트렌드 항목 id(tombstone). state=MERGED일 때만 값이 있다. 행 자체는 삭제하지 않는다.';
COMMENT ON COLUMN trend_items.created_at IS '행 생성 시각.';
COMMENT ON COLUMN trend_items.updated_at IS '행 갱신 시각(트리거로 자동 갱신).';
COMMENT ON COLUMN trend_items.merge_checked_at IS 'cluster_merge 배치가 이 항목의 임베딩 유사도 비교를 마친 시각. NULL이면 아직 미비교.';
COMMENT ON COLUMN trend_items.judgment_deadline_override IS '판정 유예 연장 시각(관리자가 D+14를 최대 D+21까지 미룰 때). NULL이면 기본 D+14.';

-- ════════════════════════════════════════════════════════════════════
-- submissions · 개별 제보
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE submissions IS '유저의 개별 트렌드 제보. 확신도(포인트)를 걸고 베팅하듯 등록한다 — 판정의 유일한 입력 데이터.';
COMMENT ON COLUMN submissions.id IS 'PK.';
COMMENT ON COLUMN submissions.user_id IS '제보한 유저.';
COMMENT ON COLUMN submissions.trend_item_id IS '이 제보가 속한 트렌드 항목(병합된 클러스터).';
COMMENT ON COLUMN submissions.raw_input IS '유저가 입력한 원문 트렌드 명칭(정규화 전).';
COMMENT ON COLUMN submissions.normalized_key IS '이 제보 시점에 계산된 정규화 키. 완전일치 병합 판단에 사용.';
COMMENT ON COLUMN submissions.confidence IS '확신도(베팅 포인트). 10/30/50 중 하나만 허용.';
COMMENT ON COLUMN submissions.source_platform IS '최초 목격 플랫폼(X, 인스타, 디시, 틱톡 등 자유텍스트).';
COMMENT ON COLUMN submissions.evidence_url IS '근거 URL. 1건 이상 필수(캡처만 있는 제보는 반려).';
COMMENT ON COLUMN submissions.one_line IS '한 줄 의미 설명(카드에 표시).';
COMMENT ON COLUMN submissions.disclosure IS '이해관계 고지 여부. 미고지 적발 시 제재 근거가 된다.';
COMMENT ON COLUMN submissions.result IS '이 제보의 판정 결과(PENDING/HIT/MISS/VOID). 유저 등급의 TI·판정건수 계산 입력.';
COMMENT ON COLUMN submissions.is_seed IS '운영진 시딩 여부. true면 점수 원장에 반영하지 않는다(클로즈드 베타 시딩 물량 구분용).';
COMMENT ON COLUMN submissions.created_at IS '제보 시각. 선점 순위(order_rank)는 이 값 기준으로 매번 파생 계산되며 저장 컬럼이 아니다.';

-- ════════════════════════════════════════════════════════════════════
-- endorsements · 동의(중복 제보 전환)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE endorsements IS '같은 트렌드에 이미 제보가 있을 때 "나도 봤다"고 동의하는 행위. 제보권을 소모하지 않는다.';
COMMENT ON COLUMN endorsements.id IS 'PK.';
COMMENT ON COLUMN endorsements.trend_item_id IS '동의 대상 트렌드 항목.';
COMMENT ON COLUMN endorsements.user_id IS '동의한 유저. 같은 항목에 유저당 1행만(유니크).';
COMMENT ON COLUMN endorsements.src_submission IS '동의의 발단이 된 중복 제보(있는 경우).';
COMMENT ON COLUMN endorsements.created_at IS '동의 시각.';

-- ════════════════════════════════════════════════════════════════════
-- verdicts · 판정 결과 (append-only)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE verdicts IS 'D+14(또는 유예 연장) 판정 결과. verdict_runner 배치만 INSERT하며 관리자도 결과를 직접 바꾸지 못한다. append-only — 재판정은 새 행을 supersedes로 쌓는다.';
COMMENT ON COLUMN verdicts.id IS 'PK.';
COMMENT ON COLUMN verdicts.trend_item_id IS '판정 대상 트렌드 항목.';
COMMENT ON COLUMN verdicts.result IS '판정 결과(HIT/MISS/VOID).';
COMMENT ON COLUMN verdicts.reach_level IS '확산 규모(L1~L4). HIT일 때만 값이 있고 MISS/VOID는 NULL.';
COMMENT ON COLUMN verdicts.score_t IS '종합 판정 점수 T(0~1, clip). 서로 다른 후속 제보자 수 / 목표치로 산정.';
COMMENT ON COLUMN verdicts.judged_at IS '실제 판정이 내려진 시각.';
COMMENT ON COLUMN verdicts.evidence_json IS '판정 시점의 order_rank·baseline 등 스냅샷. 이후 병합이 일어나도 확정 점수가 흔들리지 않게 동결해둔 근거.';
COMMENT ON COLUMN verdicts.supersedes IS '재판정 시 이전(대체된) 판정 행을 가리킨다. NULL이면 원본 판정.';
COMMENT ON COLUMN verdicts.created_at IS '행 생성 시각.';

-- ════════════════════════════════════════════════════════════════════
-- score_ledger · 점수 원장 (append-only)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE score_ledger IS '유저별 점수 변동 원장. append-only — 정정은 UPDATE가 아니라 ADJ 상쇄행 추가로만 한다.';
COMMENT ON COLUMN score_ledger.id IS 'PK.';
COMMENT ON COLUMN score_ledger.user_id IS '점수가 귀속되는 유저.';
COMMENT ON COLUMN score_ledger.submission_id IS '이 점수 변동의 근거가 된 제보. ADJ 행은 특정 제보와 무관할 수 있어 NULL 허용.';
COMMENT ON COLUMN score_ledger.verdict_id IS '이 점수 변동의 근거가 된 판정.';
COMMENT ON COLUMN score_ledger.kind IS '원장 종류(HIT/MISS/VOID/ADJ). ADJ는 관리자가 넣는 정정 상쇄행.';
COMMENT ON COLUMN score_ledger.delta IS '점수 증감분. HIT=+c·w_order·(1+m)·d, MISS=−c·0.5·d, VOID=0.';
COMMENT ON COLUMN score_ledger.reason IS '산정 근거 문자열(예: 제보 id·선점순위·확신도·확산배수 요약). ADJ는 반드시 비어있지 않아야 한다.';
COMMENT ON COLUMN score_ledger.approved_by IS 'ADJ나 100점 초과 상쇄 등 2인 승인이 필요한 원장의 승인자.';
COMMENT ON COLUMN score_ledger.approval_id IS '2인 승인 요청(approval_requests) 연결.';
COMMENT ON COLUMN score_ledger.created_at IS '원장 기록 시각. 시간감쇠(d=0.5^(경과일/90)) 계산의 기준 시각.';

-- ════════════════════════════════════════════════════════════════════
-- user_grades · 등급 스냅샷 (append-only, 파생 이력)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE user_grades IS '주 1회 grade_recalc 배치가 append하는 등급 스냅샷 이력. 점수 원장을 읽어 산출하는 파생값 — API는 이 테이블 대신 실시간 재계산을 쓴다(최대 1주 묵은 값 방지).';
COMMENT ON COLUMN user_grades.id IS 'PK.';
COMMENT ON COLUMN user_grades.user_id IS '대상 유저.';
COMMENT ON COLUMN user_grades.grade IS '산정된 등급(L0~L4).';
COMMENT ON COLUMN user_grades.trust_index IS '신뢰도 지수 TI = (HIT+2)/(HIT+MISS+5). 초기값 0.4.';
COMMENT ON COLUMN user_grades.active_score IS '활동 점수 AS(시간감쇠 반영 누적 점수).';
COMMENT ON COLUMN user_grades.judged_count IS '판정 완료 건수(HIT+MISS).';
COMMENT ON COLUMN user_grades.computed_at IS '이 스냅샷이 계산된 시각.';

-- ════════════════════════════════════════════════════════════════════
-- votes · "이거 더 뜰까요" 투표
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE votes IS '유행 여부에 대한 자유 투표. 판정과 물리적으로 분리 — 이 투표는 절대 판정 입력에 쓰이지 않는다.';
COMMENT ON COLUMN votes.id IS 'PK.';
COMMENT ON COLUMN votes.user_id IS '투표한 유저.';
COMMENT ON COLUMN votes.trend_item_id IS '투표 대상 트렌드 항목.';
COMMENT ON COLUMN votes.will_trend IS 'true=뜬다, false=안 뜬다.';
COMMENT ON COLUMN votes.created_at IS '최초 투표 시각.';
COMMENT ON COLUMN votes.updated_at IS '투표 토글(변경) 시각. 유저당 항목당 1행만 유지되고 값만 바뀐다.';

-- ════════════════════════════════════════════════════════════════════
-- watches · 워치(관심 키워드 추적)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE watches IS '유저가 관심 등록한 키워드. 실제 푸시 발송 로직은 이 테이블 밖(스코프 별도).';
COMMENT ON COLUMN watches.id IS 'PK.';
COMMENT ON COLUMN watches.user_id IS '워치를 등록한 유저.';
COMMENT ON COLUMN watches.keyword IS '유저가 등록할 때 입력한 원문 키워드.';
COMMENT ON COLUMN watches.normalized_key IS '정규화된 키워드. trend_items.normalized_key와 매칭해 관련 트렌드를 찾는다.';
COMMENT ON COLUMN watches.created_at IS '워치 등록 시각.';

-- ════════════════════════════════════════════════════════════════════
-- user_preferences · 개인화 설정
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE user_preferences IS '유저 1명당 1행. 관심 카테고리·알림 시간. 온보딩에서 생성, 설정 화면에서 갱신.';
COMMENT ON COLUMN user_preferences.user_id IS 'PK 겸 FK. 유저당 정확히 1행.';
COMMENT ON COLUMN user_preferences.categories IS '관심 카테고리 배열(온보딩에서 고른 3개). "오늘의 5개" 개인화 정렬에 사용.';
COMMENT ON COLUMN user_preferences.notify_hour IS '알림 받고 싶은 시(0~23, KST).';
COMMENT ON COLUMN user_preferences.updated_at IS '마지막 수정 시각.';

-- ════════════════════════════════════════════════════════════════════
-- trend_reads · 완독 기록
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE trend_reads IS '유저가 카드를 열람(완독)한 기록. 재로그인·기기 교체에도 유지되도록 서버가 진실.';
COMMENT ON COLUMN trend_reads.id IS 'PK.';
COMMENT ON COLUMN trend_reads.user_id IS '완독한 유저.';
COMMENT ON COLUMN trend_reads.trend_item_id IS '완독한 트렌드 항목. (user_id, trend_item_id) 유니크 — 같은 걸 두 번 읽어도 한 행.';
COMMENT ON COLUMN trend_reads.created_at IS '완독 처리 시각.';

-- ════════════════════════════════════════════════════════════════════
-- daily_selections · "오늘의 5개" 하루 고정 배정
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE daily_selections IS '유저별 "오늘의 5개"를 그날(KST 달력 기준) 하루 동안 고정하기 위한 배정 테이블. 그날 첫 요청 때 지연 생성되며, 이후 같은 날 요청은 이 행을 그대로 재사용한다.';
COMMENT ON COLUMN daily_selections.id IS 'PK.';
COMMENT ON COLUMN daily_selections.user_id IS '배정 대상 유저.';
COMMENT ON COLUMN daily_selections.selection_date IS '배정된 KST 달력 날짜.';
COMMENT ON COLUMN daily_selections.trend_item_id IS '배정된 트렌드 항목.';
COMMENT ON COLUMN daily_selections.rank IS '그날 5개 안에서의 순위(1~5). 표시 순서 고정에 사용.';
COMMENT ON COLUMN daily_selections.created_at IS '배정 생성 시각.';

-- ════════════════════════════════════════════════════════════════════
-- merge_queue · 병합 검수 큐
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE merge_queue IS '임베딩 유사도 0.75~0.85(회색지대) 항목의 관리자 병합 검수 큐. 0.85 이상은 자동 병합돼 이 큐에 안 들어가고, 0.75 미만은 신규 항목으로 남는다.';
COMMENT ON COLUMN merge_queue.id IS 'PK.';
COMMENT ON COLUMN merge_queue.new_trend_item_id IS '새로 들어온(병합 후보) 트렌드 항목.';
COMMENT ON COLUMN merge_queue.old_trend_item_id IS '유사하다고 판단된 기존 트렌드 항목(병합 대상 후보).';
COMMENT ON COLUMN merge_queue.similarity IS '두 항목 간 임베딩 코사인 유사도(0~1).';
COMMENT ON COLUMN merge_queue.status IS '검수 상태(PENDING/MERGED/VOIDED/SKIPPED).';
COMMENT ON COLUMN merge_queue.created_at IS '큐 적재 시각. 24시간 SLA 초과 시 판정 유예 자동 연장.';
COMMENT ON COLUMN merge_queue.resolved_at IS '검수 처리 시각.';
COMMENT ON COLUMN merge_queue.resolved_by IS '검수한 관리자 계정.';

-- ════════════════════════════════════════════════════════════════════
-- abuse_flags · 어뷰징 탐지 플래그
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE abuse_flags IS 'abuse_scan 배치가 탐지 결과만 기록하는 플래그. 그 자체가 제재는 아니다 — 확정은 사람이 한다.';
COMMENT ON COLUMN abuse_flags.id IS 'PK.';
COMMENT ON COLUMN abuse_flags.user_id IS '의심 대상 유저.';
COMMENT ON COLUMN abuse_flags.rule_code IS '탐지 규칙 코드(예: R-002 다중계정, R-005 상호추천링, R-007 미고지 이해관계).';
COMMENT ON COLUMN abuse_flags.severity IS '심각도(LOW/MEDIUM/HIGH).';
COMMENT ON COLUMN abuse_flags.detail IS '탐지 근거 상세(연결 그래프, 상호 endorse 비율 등).';
COMMENT ON COLUMN abuse_flags.status IS '처리 상태(OPEN/DISMISSED/ESCALATED/ACTIONED).';
COMMENT ON COLUMN abuse_flags.detected_at IS '탐지 시각. 48시간 SLA 기준.';
COMMENT ON COLUMN abuse_flags.resolved_at IS '처리 완료 시각.';
COMMENT ON COLUMN abuse_flags.resolved_by IS '처리한 관리자(유저 테이블 참조 — admin_accounts와는 별도).';

-- ════════════════════════════════════════════════════════════════════
-- appeals · 이의 제기
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE appeals IS '판정·제재·등급에 대한 유저 이의 제기. 처리 기한 5영업일(약관 명시값과 동일해야 함).';
COMMENT ON COLUMN appeals.id IS 'PK.';
COMMENT ON COLUMN appeals.user_id IS '이의 제기한 유저.';
COMMENT ON COLUMN appeals.target_type IS '이의 대상 종류(VERDICT/SANCTION/GRADE 등).';
COMMENT ON COLUMN appeals.target_id IS '이의 대상 엔티티 id.';
COMMENT ON COLUMN appeals.status IS '처리 상태(RECEIVED/ASSIGNED/REVIEWING/RESOLVED).';
COMMENT ON COLUMN appeals.decision IS '최종 결정(UPHELD 인용/PARTIAL 부분인용/REJECTED 기각). RESOLVED 상태면 반드시 값이 있어야 한다.';
COMMENT ON COLUMN appeals.assignee IS '이의 제기를 담당하는 처리자.';
COMMENT ON COLUMN appeals.reason_text IS '유저가 작성한 이의 사유.';
COMMENT ON COLUMN appeals.created_at IS '접수 시각.';
COMMENT ON COLUMN appeals.resolved_at IS '처리 완료 시각.';

-- ════════════════════════════════════════════════════════════════════
-- sanctions · 제재(상신 → 2인 승인 → 실행)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE sanctions IS '유저 제재. OPERATOR가 상신만 하고, 확정·실행은 ADMIN 2인 승인이 필요하다.';
COMMENT ON COLUMN sanctions.id IS 'PK.';
COMMENT ON COLUMN sanctions.target_user_id IS '제재 대상 유저.';
COMMENT ON COLUMN sanctions.type IS '제재 종류(WARNING/GRADE_RESET/SUSPEND 등).';
COMMENT ON COLUMN sanctions.reason IS '제재 사유(법무상 "기타 운영진 판단" 단독 의존 금지 — 열거된 사유 필요).';
COMMENT ON COLUMN sanctions.requested_by IS '상신한 관리자.';
COMMENT ON COLUMN sanctions.status IS '진행 상태(REQUESTED/APPROVED/EXECUTED/REJECTED).';
COMMENT ON COLUMN sanctions.approval_id IS '2인 승인 요청(approval_requests) 연결.';
COMMENT ON COLUMN sanctions.created_at IS '상신 시각.';
COMMENT ON COLUMN sanctions.executed_at IS '실제 제재가 집행된 시각.';

-- ════════════════════════════════════════════════════════════════════
-- admin_accounts · 관리자 계정(RBAC)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE admin_accounts IS '관리 콘솔 전용 계정. 앱 유저(users)와 분리된 별도 인증 주체.';
COMMENT ON COLUMN admin_accounts.id IS 'PK.';
COMMENT ON COLUMN admin_accounts.login_id IS '로그인 아이디. 유니크.';
COMMENT ON COLUMN admin_accounts.display_name IS '화면 표시명.';
COMMENT ON COLUMN admin_accounts.role IS '권한 역할(REVIEWER/OPERATOR/ADMIN/AUDITOR).';
COMMENT ON COLUMN admin_accounts.twofa_enabled IS '2단계 인증 사용 여부.';
COMMENT ON COLUMN admin_accounts.last_login_at IS '마지막 로그인 시각.';
COMMENT ON COLUMN admin_accounts.disabled_at IS '비활성화 시각. 90일 미접속 자동 비활성 또는 퇴사 회수 시 설정.';
COMMENT ON COLUMN admin_accounts.created_at IS '계정 생성 시각.';
COMMENT ON COLUMN admin_accounts.password_hash IS 'BCrypt 해시된 로그인 비밀번호.';
COMMENT ON COLUMN admin_accounts.seed_user_id IS '이 관리자가 클로즈드 베타 시딩 제보를 낼 때 쓰는 합성 앱 유저 계정(ADM-500).';

-- ════════════════════════════════════════════════════════════════════
-- parameter_drafts · 파라미터 스튜디오 드래프트
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE parameter_drafts IS '점수·등급 산정 파라미터(가중치·임계값·반감기 등) 변경 드래프트. 즉시반영 금지 — 시뮬레이션 → 2인 승인 → 예약 적용 순서를 강제한다.';
COMMENT ON COLUMN parameter_drafts.id IS 'PK.';
COMMENT ON COLUMN parameter_drafts.author_id IS '드래프트 작성 관리자.';
COMMENT ON COLUMN parameter_drafts.status IS '진행 상태(DRAFT/REVIEW/APPROVED/APPLIED/ROLLED_BACK).';
COMMENT ON COLUMN parameter_drafts.payload IS '변경할 파라미터 값(JSON: 가중치·임계값·반감기·승급요구치 등). 엔진의 ParameterSet으로 주입된다.';
COMMENT ON COLUMN parameter_drafts.sim_result IS '시뮬레이션 결과. 이 값 없이는 승인 요청 자체가 불가(안전장치).';
COMMENT ON COLUMN parameter_drafts.apply_mode IS '적용 방식(SCHEDULED 예약·비소급 기본 / RETROACTIVE 소급, 소급은 2인 승인+사유서 필요).';
COMMENT ON COLUMN parameter_drafts.apply_at IS '예약 적용 시각.';
COMMENT ON COLUMN parameter_drafts.approval_id IS '2인 승인 요청(approval_requests) 연결.';
COMMENT ON COLUMN parameter_drafts.superseded_by IS '이 드래프트를 대체한 이후 드래프트(롤백/버전 이력 추적).';
COMMENT ON COLUMN parameter_drafts.created_at IS '드래프트 생성 시각.';
COMMENT ON COLUMN parameter_drafts.applied_at IS '실제 적용된 시각.';
COMMENT ON COLUMN parameter_drafts.version IS '낙관적 락 카운터. 두 관리자의 동시 수정 경합 방지.';

-- ════════════════════════════════════════════════════════════════════
-- approval_requests · 2인 승인(4-eyes) 상태머신
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE approval_requests IS '유저 제재 확정·등급 수동 조정·파라미터 적용·100점 초과 상쇄 원장 등 고위험 작업에 대한 2인 승인 워크플로.';
COMMENT ON COLUMN approval_requests.id IS 'PK.';
COMMENT ON COLUMN approval_requests.action_type IS '승인 대상 작업 종류(SANCTION/GRADE_ADJUST/PARAM_APPLY/LEDGER_ADJ_OVER100).';
COMMENT ON COLUMN approval_requests.target_ref IS '승인 대상 엔티티 id.';
COMMENT ON COLUMN approval_requests.requested_by IS '요청한 관리자. 승인자와 반드시 달라야 한다.';
COMMENT ON COLUMN approval_requests.approver_1 IS '첫 번째 승인자.';
COMMENT ON COLUMN approval_requests.approver_2 IS '두 번째 승인자. 첫 번째 승인자와 반드시 달라야 한다.';
COMMENT ON COLUMN approval_requests.status IS '승인 진행 상태(PENDING/PARTIAL/APPROVED/REJECTED/EXECUTED).';
COMMENT ON COLUMN approval_requests.payload IS '승인 요청 상세 내용(JSON).';
COMMENT ON COLUMN approval_requests.created_at IS '요청 시각.';
COMMENT ON COLUMN approval_requests.resolved_at IS '승인/반려 확정 시각.';

-- ════════════════════════════════════════════════════════════════════
-- admin_audit_log · 감사 로그 (append-only, 해시체인)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE admin_audit_log IS '관리자 행위 감사 로그. 위변조 방지를 위해 prev_hash→hash로 해시체인을 이루며 append-only.';
COMMENT ON COLUMN admin_audit_log.id IS 'PK. 해시체인 순서를 보장하는 단조증가 값.';
COMMENT ON COLUMN admin_audit_log.actor_id IS '행위를 수행한 관리자 계정.';
COMMENT ON COLUMN admin_audit_log.role IS '행위 당시 관리자의 역할.';
COMMENT ON COLUMN admin_audit_log.action IS '행위 종류(MERGE/VOID/LEDGER_ADJ/PARAM_APPLY/PII_VIEW/LOGIN 등).';
COMMENT ON COLUMN admin_audit_log.target_type IS '행위 대상 엔티티 종류.';
COMMENT ON COLUMN admin_audit_log.target_id IS '행위 대상 엔티티 id.';
COMMENT ON COLUMN admin_audit_log.detail IS '행위 상세 내용(JSON).';
COMMENT ON COLUMN admin_audit_log.prev_hash IS '직전 로그 행의 hash 값(체인 연결).';
COMMENT ON COLUMN admin_audit_log.hash IS '이 행의 해시값 = H(prev_hash || 정규화된 행 내용).';
COMMENT ON COLUMN admin_audit_log.created_at IS '행위 발생 시각.';

-- ════════════════════════════════════════════════════════════════════
-- 파생 뷰 (저장 데이터 아님)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON VIEW submission_order_rank IS '제보 선점 순위. 저장 컬럼이 아니라 submissions.created_at 기준으로 매번 계산되는 파생 뷰(RANK() OVER, VOID 제외). 병합이 일어나도 전체가 자동 재정렬된다.';
COMMENT ON COLUMN submission_order_rank.id IS 'submissions.id.';
COMMENT ON COLUMN submission_order_rank.trend_item_id IS '해당 제보가 속한 트렌드 항목.';
COMMENT ON COLUMN submission_order_rank.order_rank IS '트렌드 항목 내 제보 순위(1위=최초 선점). created_at 오름차순 RANK().';

COMMENT ON VIEW user_grade_current IS 'user_grades(이력 테이블)에서 유저별 가장 최근 스냅샷만 뽑은 뷰. "현재 등급"을 조회할 때 사용.';
COMMENT ON COLUMN user_grade_current.user_id IS '대상 유저.';
COMMENT ON COLUMN user_grade_current.grade IS '가장 최근에 계산된 등급.';
COMMENT ON COLUMN user_grade_current.trust_index IS '가장 최근 TI 값.';
COMMENT ON COLUMN user_grade_current.active_score IS '가장 최근 AS 값.';
COMMENT ON COLUMN user_grade_current.judged_count IS '가장 최근 스냅샷 시점의 판정 완료 건수.';
COMMENT ON COLUMN user_grade_current.computed_at IS '가장 최근 스냅샷 계산 시각.';

-- ════════════════════════════════════════════════════════════════════
-- 인프라 테이블(도메인 밖 — 프레임워크/라이브러리가 관리)
-- ════════════════════════════════════════════════════════════════════
COMMENT ON TABLE shedlock IS 'ShedLock 라이브러리가 관리하는 분산 스케줄러 락 테이블. 여러 인스턴스가 같은 배치 잡을 동시에 실행하지 않도록 막는다. 애플리케이션 코드가 직접 다루지 않는다.';
COMMENT ON COLUMN shedlock.name IS '락 이름(잡 식별자).';
COMMENT ON COLUMN shedlock.lock_until IS '락 만료 예정 시각.';
COMMENT ON COLUMN shedlock.locked_at IS '락 획득 시각.';
COMMENT ON COLUMN shedlock.locked_by IS '락을 획득한 인스턴스 식별자.';

COMMENT ON TABLE flyway_schema_history IS 'Flyway가 자동으로 관리하는 마이그레이션 이력 테이블. 애플리케이션 도메인과 무관 — 직접 수정하지 않는다.';
