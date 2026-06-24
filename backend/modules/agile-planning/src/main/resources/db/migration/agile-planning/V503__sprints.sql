-- sprints / sprint_issues 테이블 생성 (FR-BL-02 V503) — 스프린트 Aggregate (스프린트 + 1:N 이슈 할당)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).

-- ── sprints ──────────────────────────────────────────────────────────────────
-- 스프린트 Aggregate Root. 백로그 이슈를 일정 기간(반복 주기) 단위로 묶어 진행 상태를 관리한다.
-- project_key(문자열)를 쓰는 이유: agile-planning BC 는 BC 격리상 issue-tracking 의 projects 테이블을
-- 직접 참조할 수 없어 projectKey→projectId(UUID) 변환이 불가하다. 따라서 FK 없이 문자열로 저장한다
-- (boards.project_key 선례, DATA.md §7 BC 격리).
CREATE TABLE sprints (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key VARCHAR(64) NOT NULL,        -- BC 격리: FK 아님(issue-tracking projects.key 문자열, boards 선례와 동일 길이)
    name        VARCHAR(255) NOT NULL,
    goal        TEXT,                        -- 스프린트 목표(선택). NULL=미설정
    -- status: 스프린트 생명주기. PLANNED(계획)→ACTIVE(진행)→COMPLETED(완료) 단방향.
    -- 허용값 외 유입을 DB 레벨에서 차단(애플리케이션 enum 과 이중 방어).
    status      VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
        CONSTRAINT sprints_status_allowed CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),
    start_date  DATE,                        -- 스프린트 시작일(선택). NULL=미정
    end_date    DATE,                        -- 스프린트 종료일(선택). NULL=미정
    version     BIGINT NOT NULL DEFAULT 0,   -- 낙관적 락(OCC) 버전. 동시 수정 충돌 감지용
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ NULL             -- 소프트 삭제(DATA.md §1.2). 삭제된 스프린트는 조회에서 제외(404)
);

COMMENT ON TABLE  sprints             IS '스프린트 Aggregate — 백로그 이슈를 반복 주기 단위로 묶는 작업 단위';
COMMENT ON COLUMN sprints.project_key IS 'issue-tracking projects.key 문자열(BC 격리, FK 아님)';
COMMENT ON COLUMN sprints.status      IS 'PLANNED/ACTIVE/COMPLETED — 스프린트 생명주기 상태';
COMMENT ON COLUMN sprints.version     IS '낙관적 락(OCC) 버전. 0 부터 시작, 수정마다 증가';
COMMENT ON COLUMN sprints.deleted_at  IS '소프트 삭제 시각. NULL=활성. non-NULL=삭제됨(스프린트 조회 404)';

-- 프로젝트별 스프린트 목록 조회(GET /sprints?projectKey=) 최적화 — 활성 스프린트만 인덱싱(부분 인덱스).
CREATE INDEX idx_sprints_project ON sprints (project_key) WHERE deleted_at IS NULL;

-- ── sprint_issues ────────────────────────────────────────────────────────────
-- 스프린트↔이슈 할당(1:N). 한 스프린트는 여러 이슈를 담지만, 한 이슈는 한 스프린트에만 속한다.
-- issue_key(문자열)를 쓰는 이유: BC 격리상 issue-tracking 의 issues 테이블을 FK 로 참조할 수 없다
-- (issues.key 길이 VARCHAR(20) 와 동일, V018 issue_change_history.issue_key 선례).
CREATE TABLE sprint_issues (
    sprint_id  UUID NOT NULL REFERENCES sprints (id) ON DELETE CASCADE,
    issue_key  VARCHAR(20) NOT NULL,         -- BC 격리: FK 아님(issue-tracking issues.key 문자열, 동일 길이)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- PK(sprint_id, issue_key): 같은 이슈를 같은 스프린트에 중복 할당 불가.
    PRIMARY KEY (sprint_id, issue_key),
    -- UNIQUE(issue_key): 한 이슈는 전역적으로 한 스프린트에만 속한다(1:N 보장). 다른 스프린트로 재할당하려면 먼저 제거해야 한다.
    CONSTRAINT sprint_issues_issue_key_unique UNIQUE (issue_key)
);

COMMENT ON TABLE  sprint_issues            IS '스프린트↔이슈 할당 — 한 이슈는 한 스프린트에만(issue_key 전역 UNIQUE)';
COMMENT ON COLUMN sprint_issues.issue_key  IS 'issue-tracking issues.key 문자열(BC 격리, FK 아님)';

-- sprint_id FK 조회·CASCADE 점검·스프린트별 이슈 목록 조회 최적화(FK 전용 인덱스, DATA.md §7).
-- PK leftmost prefix(sprint_id)가 이를 커버하나, 명시 인덱스로 의도를 분명히 한다.
CREATE INDEX idx_sprint_issues_sprint ON sprint_issues (sprint_id);
