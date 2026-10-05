-- V31_1이 채운 platform을 필수로. 목록은 kr.trendstage.domain.signal.Platform과 같아야 한다(SchemaV31Test가 확인).
ALTER TABLE submissions ALTER COLUMN platform SET NOT NULL;
ALTER TABLE submissions ADD CONSTRAINT submission_platform_known CHECK (platform IN
    ('DCINSIDE', 'THEQOO', 'FMKOREA', 'INSTIZ', 'X', 'INSTAGRAM', 'THREADS', 'YOUTUBE', 'TIKTOK', 'NAVER', 'ETC'));
