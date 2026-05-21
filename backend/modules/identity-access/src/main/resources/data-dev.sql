-- dev profile 전용 시드 — 로컬 개발용 alice 계정 (prod 환경 절대 실행 안 됨)
-- application-dev.yml 의 spring.sql.init.data-locations 에 의해서만 실행된다.
--
-- alice 계정: username=alice, password=password (평문)
-- password_hash 는 Argon2id m=65536,t=3,p=4 파라미터 (Argon2Params 상수) 로 생성된 인코딩 문자열.
-- 생성 방법:
--   Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
--       .hash(Argon2Params.ITERATIONS, Argon2Params.MEMORY_KB, Argon2Params.PARALLELISM,
--             "password".toCharArray())
-- 파라미터 변경 시 재생성 필요 (위 명령 한 번 실행 후 결과를 아래 INSERT 의 password_hash 자리에 박는다).

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
    '$argon2id$v=19$m=65536,t=3,p=4$+776pN3T51FGNfMM/qK0QQ$NirJnuKY/Iy4VByP8+hccGTQsx0ysD3vqKjvRrhhVv8',
    'argon2id-v1',
    NOW(),
    NOW()
) ON CONFLICT (user_id) DO NOTHING;
