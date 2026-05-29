<!-- issue-tracking BC — 이슈 코어 29 FR (CRUD/타입/담당자/본문/Resolution/PDF + 컴포넌트/버전 + 첨부/멘션/Watcher + 링크/히스토리/템플릿 + 이동) -->

# issue-tracking BC

**소속 FR**. 29개 (IS 9 + CM 3 + VR 4 + AC 2 + MN 2 + WT 1 + LK 2 + HS 2 + TM 2 + MV 2).
**책임**. 이슈/댓글/첨부/관계/이력/템플릿/이동.
**SDD 참조**. 05장 (데이터 모델), 11장 (API).
**다른 BC와의 경계**. project-workflow의 상태 전이 호출, identity-access의 권한 가드 사용, notification-dashboard 이벤트 발행. **다른 BC import 금지 — 이벤트는 pgmq**.

## §0 진입 조건

- [ ] identity-access §2.1 (`AuthenticationProvider`), §4.2 (`PERMISSION` 가드) 완료
- [ ] project-workflow §1 (FSM PoC) + §2.1 (FR-WF-01) 진입 시 의존 (이슈 상태 전이)
- [ ] notification-dashboard §1 (STOMP PoC), pgmq 트랜잭션 PoC 통과
- [ ] DATA.md §이슈키 영속성 + 소프트 삭제 규칙 숙지
- [ ] §A.3 #5 (이슈 키 prefix) 결정 — DATA.md 가이드

## §1 기술 검증

이 BC 자체의 PoC는 없음. 의존 PoC.
- pgmq 트랜잭션 일관성 → project-workflow §1
- TipTap variant 추상 (issue-body / comment / wiki) → §2.1.4 본문 에디터 작성 시점

## §2 이슈 코어 (FR-IS, 9개)

### §2.1 필수 5개 (CRUD/타입/담당자/본문/Resolution)

#### §2.1.1 FR-IS-01 — 이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `issue/crud`

- [x] D1. 도메인 — Issue Aggregate Root, IssueKey VO (책임. backend-engineer + Maxi)
- [x] D2. 명세 — Given/When/Then. 7 엣지 케이스 (중복 키/권한/전이 위반/대용량/동시 편집/소프트 삭제/키 보존) (책임. backend-engineer)
- [x] D3. 데이터 모델 — Flyway. `issues`, `issue_key_redirects`. DATA.md 영속성 (책임. db-engineer)
- [x] D4. 백엔드 — `POST/GET/PATCH/DELETE /api/v1/issues`. `@Transactional`. pgmq 이벤트 발행 동일 트랜잭션 (책임. backend-engineer + security-engineer 가드) (완료. PR #17 비즈니스 로직 + PR #23 codereview CONCERN cleanup — sealed Result port + PATCH partial RFC 7396 + PR #27 transition wiring — WorkflowResolver consumer (issue-tracking ↔ project-workflow via SPI). **현재 BLOCKER**. DEFAULT 워크플로우 미시드 + OPEN/open 케이스 불일치로 런타임 전이 동작 X — PR #28 hot-fix 진행 중)
- [x] D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합. TDD red→green→refactor (책임. backend-engineer) (완료. PR #17 단위 + PR #23 ArchUnit 2룰 + Testcontainers singleton base + Kotest property × 3 + PR #24 Flyway namespace 격리로 IssueRepositoryTest `@Disabled` 해제 + NFR-3 2회 연속 BUILD SUCCESSFUL 24s × 2 측정 통과)
- [x] D6. 프론트 UI — `IssueDetail.tsx`. TanStack Query 캐싱 + 낙관적 업데이트 (책임. designer → frontend-engineer) (완료. PR #26 — 목록(issues.index)+생성(issues.new)+상세(issues.$key, 시안2 사이드 메타패널)+요약 인라인 수정(완전 낙관적 onMutate/onError/onSettled+409 토스트)+소프트 삭제. sonner toast 도입. router 3라우트 requireAuth. **상태 전이 UI는 제외** — 백엔드 미연동(DEFAULT 워크플로우 미시드 + OPEN/open 케이스 불일치), 별 slice. 8 TDD task / 173 테스트 통과)
- [ ] D7. E2E + NFR — 생성→조회→수정→상태 전이→소프트 삭제→키 영속성 (이동 후 옛 키 redirect) (책임. qa-engineer)

> **D7 부분 통과 (PR #32, 2026-05-28)**. E2E-1~4 적용 — 생성/조회/인라인 수정/소프트 삭제/목록 제외 + 비로그인 가드 + 미존재 키 404 + UI 회귀 가드 3건 (인라인 편집 취소 / 다이얼로그 취소 / 빈 summary Zod 검증). MSW mock 환경 (backend dev 불필요). full 통과 후속 의존:
> - **상태 전이 E2E** ← FR-IS-01 D6.5 신규 slice (전이 UI 추가 — DropdownMenu + useTransitionMutation, 백엔드 wiring 은 PR #27/#28 완료).
> - **이슈 이동 + 키 redirect E2E** ← FR-IS-12 move 별 FR (백엔드 IssueKeyRedirect + Flyway + jOOQ + frontend 이동 다이얼로그 + redirect 라우트).
> - **NFR p95 측정 3건 (조회 200ms / 목록 500ms / POST 300ms)** Deferred trigger — (a) k6-load-testing + Playwright NFR 계층 도구 도입 + (b) 실 backend 환경 사전 구축, Maxi 선언으로 trigger 조정 (PR #21 §F4 패턴).
> - **권한 모델 E2E** ← 실 RBAC 가드 구현 (현재 dev/test = `AlwaysAllowIssuePermissionResolver`) 별 FR 후.

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 단건 조회 | 200ms | ___ |
| 목록 50건 | 500ms | ___ |
| `POST /issues` | 300ms | ___ |

#### §2.1.2 FR-IS-02 — 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/types`

- [x] D1. 도메인 — IssueType.hierarchyLevel + Issue.typeId (PR #36)
- [x] D2. 명세 — Epic-Subtask 계층은 hierarchyLevel 메타데이터로 (parent_id 강제는 후속 FR) (PR #36)
- [x] D3. 데이터 모델 — `issue_types.hierarchy_level` + `issues.type_id` FK + key 부분 unique (V005, PR #36)
- [x] D4. 백엔드 — 커스텀 IssueType CRUD (POST/PATCH/DELETE+reassignTo) + 표준 불변 + RFC 7807 (PR #36)
- [x] D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합 (PR #36)
- [ ] D6. 프론트 UI — 타입 셀렉터 + 아이콘 (책임. designer → frontend-engineer) — transition-e2e(PR #34) 정리 후 별 PR
- [ ] D7. E2E (책임. qa-engineer) — D6와 함께

#### §2.1.3 FR-IS-03 — 담당자 (Reporter 1 / Assignee 1 / Watchers N)

**우선순위**. 필수 | **선행**. §2.1.1, §4.3.1 (Watcher) | **Plan slug**. `issue/assignees`

- [ ] D1. 도메인 — R/A/W 역할 분리 (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issues.reporter_id/assignee_id`, `issue_watchers` (책임. db-engineer)
- [ ] D4. 백엔드 — `PATCH /api/v1/issues/{key}/assignee` 등 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 담당자 셀렉터 + Watcher 버튼 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §2.1.4 FR-IS-04 — 본문(Markdown) + 우선순위/라벨/환경/영향도

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/body`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — Markdown XSS sanitization (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `issues.body` (TEXT) + priority/environment/impact (책임. db-engineer)
- [ ] D4. 백엔드 — flexmark 렌더링 + sanitize (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 — XSS 페이로드 10종 차단 (책임. backend-engineer + security-engineer)
- [ ] D6. 프론트 UI — TipTap Atlas Editor (`issue-body` variant). 50블록 PoC 함께 검증 (§A.3 #7) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §2.1.5 FR-IS-07 — Resolution 필드 (Fixed/Won't Fix/Duplicate)

**우선순위**. 필수 | **선행**. §2.1.1, project-workflow §2.1 | **Plan slug**. `issue/resolution`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 종료 상태 진입 시 Resolution 필수 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `resolutions`, `issues.resolution_id` (책임. db-engineer)
- [ ] D4. 백엔드 — 상태 전이 가드 (Resolution 미설정 시 reject) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 종료 모달 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.2 보강 2개 (일괄 편집, 라벨 자동완성)

#### §2.2.1 FR-IS-05 — 이슈 일괄 편집 + 일괄 상태 전이

**우선순위**. 높음 | **선행**. §2.1.1 | **Plan slug**. `issue/bulk-edit`

- [ ] D1. 도메인 — BulkOp (책임. backend-engineer)
- [ ] D2. 명세 — 트랜잭션 정책, 부분 실패 처리 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (FR-IS-01 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/issues/bulk-update`. 청크 처리 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 부분 실패 시 트랜잭션 동작 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 다중 선택 + 액션 바 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §2.2.2 FR-IS-09 — 라벨 자동완성

**우선순위**. 높음 | **선행**. §2.1.1 | **Plan slug**. `issue/labels-autocomplete`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — prefix 매칭 + 사용 빈도 정렬 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `labels`, `issue_labels` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/labels?q=<prefix>` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — cmdk 콤보박스 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.3 정리 2개 (클론, PDF)

#### §2.3.1 FR-IS-06 — 이슈 클론 (옵션. 첨부/Watcher/댓글 포함)

**우선순위**. 중간 | **선행**. §2.1.1, §4.2.1, §4.3.1 | **Plan slug**. `issue/clone`

- [ ] D1. 도메인 — CloneOptions (책임. backend-engineer)
- [ ] D2. 명세 — 무엇이 복사되고 무엇이 새로 시작되는지 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용만) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/issues/{key}/clone` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 옵션 다이얼로그 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §2.3.2 FR-IS-08 — 이슈 인쇄 + PDF 출력

**우선순위**. 중간 | **선행**. §2.1.1 | **Plan slug**. `issue/pdf-export`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — PDF 레이아웃 + 페이지 헤더/푸터 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용만) (책임. db-engineer)
- [ ] D4. 백엔드 — `openhtmltopdf` + `pdfbox`. `GET /api/v1/issues/{key}/pdf` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — PDF 바이너리 검증 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 인쇄 버튼 + 다운로드 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 컴포넌트 / 버전 (7개)

### §3.1 컴포넌트 (FR-CM, 3개)

#### §3.1.1 FR-CM-01 — 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/components`

- [ ] D1. 도메인 — Component Aggregate (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `components(lead_user_id)` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD API (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 컴포넌트 관리 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.1.2 FR-CM-02 — 이슈에 다중 컴포넌트 할당

**우선순위**. 필수 | **선행**. §3.1.1 | **Plan slug**. `issue/components-assign`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_components` 다대다 (책임. db-engineer)
- [ ] D4. 백엔드 — 이슈 PATCH 확장 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 다중 셀렉터 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.1.3 FR-CM-03 — 컴포넌트별 기본 담당자 자동 할당

**우선순위**. 높음 | **선행**. §3.1.1, §2.1.3 | **Plan slug**. `issue/components-default-assignee`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 다중 컴포넌트 시 우선순위 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 이슈 생성/컴포넌트 변경 trigger (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 자동 표시 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 버전 (FR-VR, 4개)

#### §3.2.1 FR-VR-01 — 버전 생성 + 시작일/릴리즈 예정일

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/versions`

- [ ] D1. 도메인 — Version Aggregate (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `versions` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD API (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.2.2 FR-VR-02 — 버전 상태 (Unreleased/Released/Archived)

**우선순위**. 필수 | **선행**. §3.2.1 | **Plan slug**. `issue/versions-status`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 상태 전이 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `versions.status` (책임. db-engineer)
- [ ] D4. 백엔드 — 상태 전이 API + 가드 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.2.3 FR-VR-03 — Affects/Fix Version 연결

**우선순위**. 필수 | **선행**. §3.2.1 | **Plan slug**. `issue/versions-link`

- [ ] D1. 도메인 — Affects vs Fix 의미 (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_affects_versions`, `issue_fix_versions` (책임. db-engineer)
- [ ] D4. 백엔드 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 버전 셀렉터 2종 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §3.2.4 FR-VR-04 — 버전 릴리즈 노트 자동 생성

**우선순위**. 중간 | **선행**. §3.2.1, §3.2.3 | **Plan slug**. `issue/versions-release-notes`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — Markdown 템플릿 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/versions/{id}/release-notes` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 미리보기 + 복사 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 첨부 / 멘션 / Watcher (5개)

### §4.1 멘션 (FR-MN, 2개)

#### §4.1.1 FR-MN-01 — 본문/댓글 @멘션 + 즉시 알림

**우선순위**. 필수 | **선행**. §2.1.4, §4.3.1 | **Plan slug**. `issue/mentions`

- [ ] D1. 도메인 — Mention 이벤트 (책임. backend-engineer)
- [ ] D2. 명세 — `@username` 파싱 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (notification 이벤트 발행만) (책임. db-engineer)
- [ ] D4. 백엔드 — 본문/댓글 저장 시 mention 추출 → pgmq 이벤트 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 멘션 렌더링 (강조) (책임. designer → frontend-engineer)
- [ ] D7. E2E — 멘션 → Inbox 도착 (책임. qa-engineer)

#### §4.1.2 FR-MN-02 — 멘션 자동완성

**우선순위**. 높음 | **선행**. §4.1.1 | **Plan slug**. `issue/mentions-autocomplete`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — prefix 매칭 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (users 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/users/autocomplete?q=` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — `@` 트리거 popover (TipTap 확장) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.2 첨부 (FR-AC, 2개)

#### §4.2.1 FR-AC-01 — 첨부 업로드 (최대 100MB/파일)

**우선순위**. 필수 | **선행**. §2.1.1, MinIO 인프라 | **Plan slug**. `issue/attachments`

- [ ] D1. 도메인 — Attachment Aggregate (책임. backend-engineer)
- [ ] D2. 명세 — MIME 화이트리스트 + 크기 제한 + 바이러스 스캔 (ClamAV 후속) (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `attachments(minio_key)` (책임. db-engineer)
- [ ] D4. 백엔드 — Presigned URL `POST /api/v1/issues/{key}/attachments/upload-url` (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 — Testcontainers MinIO (책임. backend-engineer)
- [ ] D6. 프론트 UI — react-dropzone + 직접 PUT (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §4.2.2 FR-AC-02 — 첨부 미리보기 (이미지/PDF/동영상)

**우선순위**. 높음 | **선행**. §4.2.1 | **Plan slug**. `issue/attachments-preview`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — MIME 별 렌더러 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — Presigned GET URL (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — react-pdf + img + video.js (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.3 Watcher (FR-WT, 1개)

#### §4.3.1 FR-WT-01 — Watcher 추가/제거 + 자동 Watcher

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/watchers`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — Reporter/Assignee 자동 Watcher (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_watchers` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST/DELETE /api/v1/issues/{key}/watchers` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Watch 버튼 + 카운트 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §5 링크 / 히스토리 / 템플릿 (6개)

### §5.1 히스토리 (FR-HS, 2개)

#### §5.1.1 FR-HS-01 — 이슈 변경 이력 (필드/댓글/첨부/전이)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/history`

- [ ] D1. 도메인 — IssueHistoryEntry (책임. backend-engineer)
- [ ] D2. 명세 — 무엇을 기록하고 무엇을 기록 안 하는지 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_history(field, old_value, new_value, changed_by)` (책임. db-engineer)
- [ ] D4. 백엔드 — `IssueEventListener` (pgmq consumer) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — (조회는 §5.1.2) (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §5.1.2 FR-HS-02 — 히스토리 조회 UI

**우선순위**. 필수 | **선행**. §5.1.1 | **Plan slug**. `issue/history-ui`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 페이지네이션, 필드 필터 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/issues/{key}/history` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 타임라인 형식 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.2 템플릿 (FR-TM, 2개)

#### §5.2.1 FR-TM-01 — 프로젝트+타입별 본문 템플릿

**우선순위**. 필수 | **선행**. §2.1.1, §2.1.4 | **Plan slug**. `issue/templates`

- [ ] D1. 도메인 — IssueTemplate (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_templates(project_id, type_id, body)` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD API + 이슈 생성 시 적용 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 템플릿 관리 페이지 + 생성 시 자동 적용 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §5.2.2 FR-TM-02 — 템플릿 변수 (작성자/일자/프로젝트)

**우선순위**. 높음 | **선행**. §5.2.1 | **Plan slug**. `issue/template-vars`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 변수 종류 + 치환 시점 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 변수 치환 엔진 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 변수 자동완성 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.3 링크 (FR-LK, 2개)

#### §5.3.1 FR-LK-01 — 링크 (blocks/relates/duplicates/clones/parent-child)

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/links`

- [ ] D1. 도메인 — LinkType (책임. backend-engineer)
- [ ] D2. 명세 — 양방향성 + cycle 검출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_links(src, dst, link_type)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST/DELETE /api/v1/issues/{key}/links` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — cycle 케이스 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 링크 추가/제거 패널 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

#### §5.3.2 FR-LK-02 — 링크 그래프 시각화

**우선순위**. 중간 | **선행**. §5.3.1 | **Plan slug**. `issue/links-graph`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 노드/엣지 표현 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/issues/{key}/graph` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — SVG 또는 force-directed lib (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §6 이슈 이동 (FR-MV, 2개)

### §6.1.1 FR-MV-01 — 프로젝트 간 이슈 이동

**우선순위**. 필수 | **선행**. §2.1.1 | **Plan slug**. `issue/move`

- [ ] D1. 도메인 — IssueMoveOperation (책임. backend-engineer)
- [ ] D2. 명세 — 새 이슈 키 생성 + 옛 키 redirect (DATA.md §이슈키 영속성) (책임. backend-engineer + Maxi)
- [x] D3. 데이터 모델 — `issue_key_redirects(old_key, new_key, moved_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/issues/{key}/move` 트랜잭션 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 옛 키로 조회 시 308 redirect (책임. backend-engineer)
- [ ] D6. 프론트 UI — 이동 다이얼로그 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §6.1.2 FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지

**우선순위**. 필수 | **선행**. §6.1.1, §5.1.1, §5.3.1 | **Plan slug**. `issue/move-preserve`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 히스토리/링크/Watcher/첨부 보존 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (활용. id 보존 + key만 변경) (책임. db-engineer)
- [ ] D4. 백엔드 — 이동 시 모든 FK 보존 검증 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 이동 전후 invariant 비교 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 이동 후 페이지 자동 갱신 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR issue-tracking BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 이슈 단건 조회 | 200ms | ___ | k6 |
| 이슈 목록 50건 | 500ms | ___ | k6 |
| 이슈 생성 | 300ms | ___ | k6 (pgmq 이벤트 포함) |
| 이슈 이동 | 1s | ___ | k6 (트랜잭션 + redirect 등록) |
| 첨부 업로드 (100MB) | 30s | ___ | Playwright + MinIO |
| 첨부 미리보기 로딩 | 1s | ___ | Playwright |
| 히스토리 50건 조회 | 500ms | ___ | k6 |
| LCP (이슈 페이지) | 2.5s | ___ | Lighthouse CI |
| INP | 200ms | ___ | Lighthouse CI |
| 메인 번들 (gzip) | 200KB | ___ | bundle-analyzer |
| WCAG 2.1 AA | 0 violations | ___ | axe-core |
| XSS 페이로드 차단율 | 100% | ___ | 보안 페이로드 10종 |
| 이슈 키 영속성 (이동 후 redirect) | 308 | ___ | Playwright |

### BC 완료 조건

- [ ] §2~§6 (29 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] DATA.md §이슈키 영속성 자가 점검
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "issue-tracking BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "issue-tracking BC 완료"
