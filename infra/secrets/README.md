# infra/secrets

백엔드 컨테이너에 읽기 전용으로 마운트되는 시크릿 파일 자리다(컨테이너 안 경로 `/run/secrets/trd/`).
이 폴더에서는 이 README만 커밋되고 나머지 파일은 전부 gitignore 대상이다.

| 파일 | 용도 | `infra/.env` 설정 |
| ---- | ---- | ----------------- |
| `firebase-adminsdk.json` | Firebase 서비스 계정 키 — Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → 새 비공개 키 생성 | `FIREBASE_CREDENTIALS_PATH=/run/secrets/trd/firebase-adminsdk.json` |
