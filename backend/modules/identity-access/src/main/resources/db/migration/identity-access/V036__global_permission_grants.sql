-- 전역 권한 부여 매트릭스 — 사용자/그룹에게 전역 권한코드를 부여한다 (FR-PM-10)
--
-- 권한 코드 CREATE_PROJECT 는 SDD 12(12-permissions.md) 정본 — 프로젝트 생성 권한.
-- 근거: FR-PM-10 · ADR docs/decisions/2026-07-17-global-permission-grants.md
--       (FR-PM-08 ADR D3 "미래에 전역 권한이 세분화되면 그때 전역 매트릭스를 도입한다" 트리거 발동).
-- 선례: V015__user_groups.sql (grantee GROUP 대상), V007__project_memberships.sql (FK 없는 참조).
--
-- role_permissions 를 못 쓰는 이유: V008:25 CHECK (role IN ('PROJECT_ADMIN','MEMBER')) — 전역 축 없음.
-- project_permission_scheme 를 못 쓰는 이유: V008:39 project_id 가 PK — CREATE_PROJECT 는 프로젝트가
-- 생기기 전에 판정해야 하므로 판정 시점에 그 키가 없다 (ADR D-1).
-- init_codegen.sql 미러 불요 — identity-access 는 jOOQ 미사용(JdbcTemplate), 해당 파일 자체가 없다.
--
-- grantee_id 에 FK 없음: USER->users / GROUP->user_groups 다형 참조라 단일 FK 로 표현 불가.
-- project_memberships.project_id 와 같은 선례 (ADR D-4).
-- 고아 행의 운명은 grantee 종류마다 다르다 (ADR D-4 — "자연 탈락"으로 뭉뚱그리지 말 것):
--   GROUP — 판정 술어가 group_memberships 를 경유하고 V015:12-13 이 양 FK 를 ON DELETE CASCADE 로
--           걸어서, 그룹 삭제 시 멤버십이 사라지고 남은 grant 행은 아무에게도 매칭되지 않는다.
--   USER  — 판정 술어의 USER 가지에 users JOIN 이 아예 없어 탈락 기전이 없다. 고아 행이 영구 잔존한다.
--           서비스 층 존재 검증(부여 시점)과 목록 API 노출(사후 발견)이 유일한 방어이며,
--           자동 정리 기전은 없다 (ADR 잔여 위험 1).
--
-- 주의(Flyway V번호): 머지 직전 identity-access 최신 번호를 재확인할 것
-- (V035 가 같은 사고로 V034->V035 재번호된 이력).
CREATE TABLE global_permission_grants (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    permission   VARCHAR(64) NOT NULL CHECK (permission IN ('CREATE_PROJECT')),
    grantee_type VARCHAR(16) NOT NULL CHECK (grantee_type IN ('USER','GROUP')),
    grantee_id   UUID        NOT NULL,
    granted_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (permission, grantee_type, grantee_id)
);

-- 인덱스 추가 없음.
-- 위 UNIQUE(permission, grantee_type, grantee_id) 가 정확히 같은 3컬럼·같은 순서의 btree 를 이미 만든다.
-- hasGrant 술어가 그 인덱스를 그대로 탄다. 별도 인덱스는 100% 중복이라 쓰기 증폭만 낳는다.
-- V015__user_groups.sql:18-19 가 세운 기준("복합 PK 선두라 인덱스 자동 생성 — 별도 불요")을 따른다.
-- group_memberships(user_id) 조회는 ix_group_memberships_user(V015:19)가 이미 커버한다.
--
-- UNIQUE 멱등성의 전제: permission/grantee_type/grantee_id 세 컬럼이 모두 NOT NULL 이다.
-- PostgreSQL UNIQUE 는 기본 NULLS DISTINCT 라 nullable 컬럼이 섞이면 같은 값이 중복 삽입된다.

COMMENT ON TABLE  global_permission_grants              IS '전역 권한 부여 — 사용자/그룹에게 프로젝트와 무관한 전역 권한코드를 부여한다 (FR-PM-10)';
COMMENT ON COLUMN global_permission_grants.permission   IS '전역 권한코드. SDD 12(12-permissions.md) 정본. 현재 CREATE_PROJECT 1종. 코드 추가 시 이 CHECK 도 확장';
COMMENT ON COLUMN global_permission_grants.grantee_type IS '부여 대상 종류. USER(users.id) 또는 GROUP(user_groups.id)';
COMMENT ON COLUMN global_permission_grants.grantee_id   IS 'grantee_type 에 따라 users.id 또는 user_groups.id. 다형 참조라 DB FK 없음 (ADR D-4)';
COMMENT ON COLUMN global_permission_grants.granted_by   IS '이 grant 를 부여한 SYSTEM_ADMIN 의 users.id. 감사 흔적. 회수(revoke)는 hard delete 라 회수 측 기록은 없다 (ADR D-5)';
