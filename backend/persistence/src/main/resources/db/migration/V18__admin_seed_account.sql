-- V18 · ADM-500 운영자별 시딩 전용 합성 유저 계정 매핑
ALTER TABLE admin_accounts ADD COLUMN seed_user_id UUID REFERENCES users(id);
