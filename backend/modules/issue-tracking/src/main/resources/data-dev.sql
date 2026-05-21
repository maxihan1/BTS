-- issue-tracking dev seed — ATLAS 프로젝트 1건. Project Management 후속 PR 도입 시 검토. ADR 2026-05-22-issue-key-prefix-policy §본 PR 적용 범위
-- dev/staging 전용 — spring.sql.init.data-locations(application-dev.yml) 에 의해서만 실행된다.
-- prod 환경에서는 application-prod.yml 에 data-locations 미설정이므로 절대 실행 안 됨.

-- 1. dev/staging 전용 — ATLAS 프로젝트 seed
INSERT INTO projects (id, key, name, key_sequence)
VALUES ('00000000-0000-0000-0000-000000000001', 'ATLAS', 'Atlas Issues', 0)
ON CONFLICT (key) DO NOTHING;
