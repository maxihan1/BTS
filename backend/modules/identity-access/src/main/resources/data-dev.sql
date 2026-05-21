-- dev profile 전용 시드 — 로컬 개발용 alice 계정 (prod 환경 절대 실행 안 됨)
-- application-dev.yml 의 spring.sql.init.data-locations 에 의해서만 실행된다.
--
-- alice 계정: username=alice, password=password (평문)
-- password_hash 는 Argon2id m=65536,t=3,p=4 파라미터로 생성된 인코딩 문자열.
-- 생성 방법:
--   Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
--       .hash(3, 65536, 4, "password".toCharArray())
--
-- TODO: backend-engineer 가 실제 Argon2id 해시로 교체 필요.
-- 현재 값은 placeholder 이며 실제 로그인이 동작하지 않는다.
-- 참고: Argon2Params (ITERATIONS=3, MEMORY_KB=65536, PARALLELISM=4)

INSERT INTO users (id, username, email, display_name, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'alice',
    'alice@bts.local',
    'Alice (Dev Seed)',
    NOW(),
    NOW()
) ON CONFLICT (username) DO NOTHING;

INSERT INTO local_credentials (user_id, password_hash, algo_version, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '<ARGON2_HASH_HERE>',
    'argon2id-v1',
    NOW(),
    NOW()
) ON CONFLICT (user_id) DO NOTHING;
