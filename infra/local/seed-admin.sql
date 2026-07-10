-- 로컬 테스트용 admin@bts.com 시스템 관리자 계정 시드 (Local provider, 비밀번호=password). prod 절대 사용 금지.
--
-- 배경. 조립 앱(prod 프로파일)은 spring.sql.init.mode=never 라 data-dev.sql 이 자동 실행되지 않는다.
--   따라서 로컬 테스트 계정은 이 스크립트를 실행 중 postgres 컨테이너에 수동 적용한다.
--
-- 실행:
--   docker exec -i bts-postgres psql -U bts -d bts < infra/local/seed-admin.sql
--
-- 반복 실행 안전(ON CONFLICT DO NOTHING). `down -v` 로 볼륨 삭제 후에도 재적용 가능.
-- 로그인. Local provider / username=admin@bts.com / password=password.
--   admin@bts.com 은 SYSTEM_ADMIN 이라 최초 로그인 시 MFA 등록 게이트를 거친다(설계된 보안).

-- 1. 사용자 — LocalProvider 는 findByUsername 으로 조회하므로 username 이 곧 로그인 ID.
--    화면에 admin@bts.com 을 입력하도록 username 을 이메일과 동일하게 둔다.
INSERT INTO users (id, username, email, display_name, display_name_source, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    'admin@bts.com',
    'admin@bts.com',
    'Admin (Local Seed)',
    'USER',
    NOW(),
    NOW()
) ON CONFLICT (username) DO NOTHING;

-- 2. 로컬 비밀번호 — Argon2id 인코딩 해시. 평문 "password" (alice 시드와 동일한 검증된 해시 재사용).
INSERT INTO local_credentials (user_id, password_hash, algo_version, must_change_password, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '$argon2id$v=19$m=65536,t=3,p=4$+776pN3T51FGNfMM/qK0QQ$NirJnuKY/Iy4VByP8+hccGTQsx0ysD3vqKjvRrhhVv8',
    'argon2id-v1',
    false,
    NOW(),
    NOW()
) ON CONFLICT (user_id) DO NOTHING;

-- 3. 시스템 관리자 승격 — system_role_assignments (role CHECK = 'SYSTEM_ADMIN').
INSERT INTO system_role_assignments (user_id, role)
VALUES ('00000000-0000-0000-0000-000000000002', 'SYSTEM_ADMIN')
ON CONFLICT (user_id, role) DO NOTHING;
