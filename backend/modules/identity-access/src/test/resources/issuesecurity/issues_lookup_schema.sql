-- identity-access 테스트 전용 issues 테이블 수동 스키마 (cross-BC drift 방어용)
--
-- ⚠️ 경고: issues 테이블은 issue-tracking BC 소유다. identity-access 는 issue-tracking 에
-- 의존하지 않으므로(ADR D2 배포 불변식) 실 Flyway 마이그레이션을 가져올 수 없다. 따라서
-- JdbcIssueSecurityLookup 통합테스트는 이 수동 스키마를 사용한다.
--
-- ⚠️ 동기화 의무: 아래 다섯 컬럼은 JdbcIssueSecurityLookup.SQL_LOOKUP 이 읽는 컬럼이다.
-- issue-tracking 의 실제 issues 테이블에서 이 컬럼들의 이름/타입이 바뀌면 운영에서 lookup 이
-- 깨진다. JdbcIssueSecurityLookupIntegrationTest 의 컬럼 존재/타입 단언 테스트가 이 파일과
-- lookup SQL 의 정합은 잡지만, issue-tracking 실 스키마와의 drift 는 cross-module 의존 부재로
-- 자동 폐쇄 불가하다(ProjectDirectory 의 수동 projects 스키마와 동형 한계). issues DDL 변경 시
-- 이 파일을 수동으로 맞출 것.
--
-- lookup 의존 컬럼:
--   key               VARCHAR  — 이슈 키(조회 키)
--   reporter_id       UUID     — 보고자(NOT NULL)
--   assignee_id       UUID     — 담당자(NULL 가능)
--   security_level_id UUID     — 보안 등급(NULL=공개)
--   deleted_at        TIMESTAMPTZ — 소프트삭제 시각(NULL=활성)
CREATE TABLE IF NOT EXISTS issues (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key               VARCHAR(20)  NOT NULL UNIQUE,
    reporter_id       UUID         NOT NULL,
    assignee_id       UUID         NULL,
    security_level_id UUID         NULL,
    deleted_at        TIMESTAMPTZ  NULL
);
