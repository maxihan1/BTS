-- issue-tracking dev seed — ATLAS 프로젝트 1건. Project Management 후속 PR 도입 시 검토. ADR 2026-05-22-issue-key-prefix-policy §본 PR 적용 범위
--
-- 목적: FR-IS-01 이슈 CRUD 개발 중 issues.project_id FK 에 바인딩할 결정적 프로젝트가 필요하다.
--   본 seed 는 로컬 dev 및 staging 환경에서만 자동 삽입되며, 반복 실행에 안전(ON CONFLICT DO NOTHING)하다.
--
-- dev/staging 전용 — spring.sql.init.data-locations(application-dev.yml) 에 의해서만 실행된다.
-- prod 환경에서는 application-prod.yml 에 data-locations 미설정이므로 절대 실행 안 됨.
--
-- UUID 00000000-0000-0000-0000-000000000001 는 dev seed 전용 결정적 UUID.
--   테스트 코드에서 하드코딩 참조 가능. 실제 사용자 입력 UUID 와 충돌하지 않는다(Nil UUID 공간).
--
-- 후속 PR (Project Management BC) 에서 프로젝트 생성 API 가 도입되면,
--   본 seed 의 역할(개발 편의 초기값)은 유지하되 API 테스트용 fixture 와 역할을 분리할 것.

-- 1. dev/staging 전용 — ATLAS 프로젝트 seed
INSERT INTO projects (id, key, name, key_sequence)
VALUES ('00000000-0000-0000-0000-000000000001', 'ATLAS', 'Atlas Issues', 0)
ON CONFLICT (key) DO NOTHING;
