-- 앱 사용자 플로우 테스트 데이터. Flyway 마이그레이션이 아니라 수동 적용 스크립트다
-- (db/migration/ 밖에 있어 Flyway가 자동으로 돌리지 않는다).
--
-- 적용 방법:
--   docker exec -i trd-db psql -U postgres -d trd < backend/persistence/src/main/resources/db/seed/app_flow_test_data.sql
--
-- 몇 번을 다시 실행해도 안전하다 — seed_user_a가 이미 있으면 통째로 건너뛴다(멱등).
-- 로그인 테스트: 아래 firebase_uid로 Firebase 커스텀 토큰을 발급하면 해당 계정으로 바로 로그인 가능.
--
--   seed_user_a (trd-seed-user-a) — 초보 유저: 판정 6건(4 HIT·2 MISS) → 등급 L1(제보자)
--   seed_user_b (trd-seed-user-b) — 활동 많은 유저: 판정 15건(12 HIT·3 MISS) → 등급 L2(탐지자)
--   seed_user_c (trd-seed-user-c) — 개인화(밈/챌린지/제품)·워치·완독 체험용
--
-- 등급 임계값(도메인 GradePolicy 기준): L1 판정≥5·TI≥0.35·AS≥30 / L2 판정≥15·TI≥0.45·AS≥150
-- (원장 delta는 실제 점수공식 대신 계산이 쉬운 고정값 HIT=+20/MISS=-10을 씀 — 등급 데모용)

DO $$
DECLARE
    user_a uuid := '5eed0000-0001-0000-0000-00000000000a';
    user_b uuid := '5eed0000-0001-0000-0000-00000000000b';
    user_c uuid := '5eed0000-0001-0000-0000-00000000000c';
    v_item uuid;
    v_sub uuid;
    v_verdict uuid;
    v_norm text;
    v_result text;
    v_reach text;
    rec record;
    i int;
    p int;
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE handle = 'seed_user_a') THEN
        RAISE NOTICE '시드 데이터가 이미 적용되어 있습니다 — 건너뜁니다.';
        RETURN;
    END IF;

    -- 1) 유저 3명 + 관심 카테고리
    INSERT INTO users (id, handle, firebase_uid) VALUES
        (user_a, 'seed_user_a', 'trd-seed-user-a'),
        (user_b, 'seed_user_b', 'trd-seed-user-b'),
        (user_c, 'seed_user_c', 'trd-seed-user-c');

    INSERT INTO user_preferences (user_id, categories, notify_hour) VALUES
        (user_a, ARRAY['MEME','SLANG','ETC'], 9),
        (user_b, ARRAY['PRODUCT','CHALLENGE','PERSON_CHANNEL'], 20),
        (user_c, ARRAY['MEME','CHALLENGE','PRODUCT'], 12);

    -- 2) 아직 판정 안 된 항목 6개 — 카테고리·확산 단계(SEED~FADING) 다양하게,
    --    여러 유저가 서로 다른 플랫폼에서 제보한 것처럼 구성. "오늘의 5개"/개인화 확인용.
    FOR rec IN
        SELECT * FROM (VALUES
            (1, '무설탕 첼로 챌린지',   'MEME'::trend_category,           ARRAY['X']),
            (2, '출근길 지하철 밈',     'CHALLENGE'::trend_category,      ARRAY['틱톡','인스타']),
            (3, '반값 텀블러 오픈런',   'PRODUCT'::trend_category,        ARRAY['디시','X','인스타']),
            (4, '억까 신조어',          'SLANG'::trend_category,          ARRAY['디시','X','인스타','틱톡']),
            (5, '신인 버추얼 유튜버',   'PERSON_CHANNEL'::trend_category, ARRAY['디시','X','인스타','틱톡','유튜브']),
            (6, '전국 편의점 콜라보',   'ETC'::trend_category,            ARRAY['디시','X','인스타','틱톡','유튜브','페이스북'])
        ) AS t(idx, name, cat, platforms)
    LOOP
        v_item := ('5eed0000-0002-0000-0000-' || lpad(rec.idx::text, 12, '0'))::uuid;
        v_norm := lower(regexp_replace(btrim(rec.name), '\s+', ' ', 'g'));

        INSERT INTO trend_items (id, canonical_name, normalized_key, category, state, first_seen_at)
        VALUES (v_item, rec.name, v_norm, rec.cat, 'PENDING', now() - ((11 - rec.idx) || ' days')::interval);

        FOR p IN 1 .. array_length(rec.platforms, 1) LOOP
            v_sub := ('5eed0000-0005-0000-' || lpad(rec.idx::text, 4, '0') || '-' || lpad(p::text, 12, '0'))::uuid;
            INSERT INTO submissions (id, user_id, trend_item_id, raw_input, normalized_key, confidence,
                                      source_platform, platform, evidence_url, one_line, result, created_at)
            VALUES (
                v_sub,
                CASE (p % 3) WHEN 0 THEN user_a WHEN 1 THEN user_b ELSE user_c END,
                v_item, rec.name, v_norm,
                (ARRAY[10, 30, 50])[1 + ((rec.idx + p) % 3)],
                rec.platforms[p],
                'ETC',
                'https://example.com/evidence/' || rec.idx || '-' || p,
                rec.name || ' 제보',
                'PENDING',
                (now() - ((11 - rec.idx) || ' days')::interval) + (p || ' hours')::interval
            );
        END LOOP;
    END LOOP;

    -- 3) 판정 완료 항목 — seed_user_a: 4 HIT + 2 MISS (등급 L1 근처)
    FOR i IN 1..6 LOOP
        v_item := ('5eed0000-0003-0000-0000-' || lpad(i::text, 12, '0'))::uuid;
        v_norm := lower(regexp_replace(btrim('판정완료 테스트 항목 A' || i), '\s+', ' ', 'g'));
        IF i <= 4 THEN
            v_result := 'HIT'; v_reach := (ARRAY['L1', 'L2', 'L3', 'L4'])[i];
        ELSE
            v_result := 'MISS'; v_reach := NULL;
        END IF;

        INSERT INTO trend_items (id, canonical_name, normalized_key, category, state, first_seen_at)
        VALUES (v_item, '판정완료 테스트 항목 A' || i, v_norm, 'MEME', 'RESOLVED', now() - interval '25 days');

        v_sub := gen_random_uuid();
        INSERT INTO submissions (id, user_id, trend_item_id, raw_input, normalized_key, confidence,
                                  source_platform, platform, evidence_url, one_line, result, created_at)
        VALUES (v_sub, user_a, v_item, '판정완료 테스트 항목 A' || i, v_norm, 30, 'X', 'ETC',
                'https://example.com/evidence/a-' || i, '테스트 제보 A' || i,
                v_result::submission_result, now() - interval '25 days');

        v_verdict := gen_random_uuid();
        INSERT INTO verdicts (id, trend_item_id, result, reach_level, score_t, judged_at, evidence_json)
        VALUES (v_verdict, v_item, v_result::verdict_result, v_reach::reach_level,
                CASE WHEN v_result = 'HIT' THEN 0.6 ELSE 0.1 END,
                now() - interval '11 days', '{}'::jsonb);

        INSERT INTO score_ledger (id, user_id, submission_id, verdict_id, kind, delta, reason)
        VALUES (gen_random_uuid(), user_a, v_sub, v_verdict, v_result::ledger_kind,
                CASE WHEN v_result = 'HIT' THEN 20.0 ELSE -10.0 END,
                CASE WHEN v_result = 'HIT' THEN '시드 데이터: 적중' ELSE '시드 데이터: 빗나감' END);
    END LOOP;

    -- 4) 판정 완료 항목 — seed_user_b: 12 HIT + 3 MISS (등급 L2 근처)
    FOR i IN 1..15 LOOP
        v_item := ('5eed0000-0004-0000-0000-' || lpad(i::text, 12, '0'))::uuid;
        v_norm := lower(regexp_replace(btrim('판정완료 테스트 항목 B' || i), '\s+', ' ', 'g'));
        IF i <= 12 THEN
            v_result := 'HIT'; v_reach := (ARRAY['L1', 'L2', 'L3', 'L4'])[1 + (i % 4)];
        ELSE
            v_result := 'MISS'; v_reach := NULL;
        END IF;

        INSERT INTO trend_items (id, canonical_name, normalized_key, category, state, first_seen_at)
        VALUES (v_item, '판정완료 테스트 항목 B' || i, v_norm,
                (ARRAY['PRODUCT', 'CHALLENGE', 'PERSON_CHANNEL'])[1 + (i % 3)]::trend_category,
                'RESOLVED', now() - interval '30 days');

        v_sub := gen_random_uuid();
        INSERT INTO submissions (id, user_id, trend_item_id, raw_input, normalized_key, confidence,
                                  source_platform, platform, evidence_url, one_line, result, created_at)
        VALUES (v_sub, user_b, v_item, '판정완료 테스트 항목 B' || i, v_norm, 30, 'X', 'ETC',
                'https://example.com/evidence/b-' || i, '테스트 제보 B' || i,
                v_result::submission_result, now() - interval '30 days');

        v_verdict := gen_random_uuid();
        INSERT INTO verdicts (id, trend_item_id, result, reach_level, score_t, judged_at, evidence_json)
        VALUES (v_verdict, v_item, v_result::verdict_result, v_reach::reach_level,
                CASE WHEN v_result = 'HIT' THEN 0.6 ELSE 0.1 END,
                now() - interval '16 days', '{}'::jsonb);

        INSERT INTO score_ledger (id, user_id, submission_id, verdict_id, kind, delta, reason)
        VALUES (gen_random_uuid(), user_b, v_sub, v_verdict, v_result::ledger_kind,
                CASE WHEN v_result = 'HIT' THEN 20.0 ELSE -10.0 END,
                CASE WHEN v_result = 'HIT' THEN '시드 데이터: 적중' ELSE '시드 데이터: 빗나감' END);
    END LOOP;

    -- 5) 워치: seed_user_b가 아직 판정 안 된 3번(반값 텀블러 오픈런)·5번(신인 버추얼 유튜버) 항목 워치
    INSERT INTO watches (user_id, keyword, normalized_key)
    SELECT user_b, canonical_name, normalized_key FROM trend_items
    WHERE id IN ('5eed0000-0002-0000-0000-000000000003', '5eed0000-0002-0000-0000-000000000005');

    -- 6) 완독: seed_user_c가 1번·2번 항목은 이미 읽음, 나머지는 안읽음으로 남겨 혼재 상태 확인
    INSERT INTO trend_reads (user_id, trend_item_id)
    VALUES
        (user_c, '5eed0000-0002-0000-0000-000000000001'),
        (user_c, '5eed0000-0002-0000-0000-000000000002');

    RAISE NOTICE '시드 데이터 적용 완료: 유저 3명, 미판정 항목 6개, 판정완료 항목 21개(A 6 + B 15)';
END $$;
