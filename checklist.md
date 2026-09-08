# 체크리스트 — 프로젝트 소유 워크플로우

스펙 `docs/specs/2026-09-08-project-owned-workflows.md` · 계획 `docs/plans/2026-09-08-project-owned-workflows.md`

## 착수 전 (Maxi 확인)

- [ ] FR 처리 — 기존 FR 범위 확장인가 새 FR 인가 (계획 §미해결)
- [ ] 스코프 타입 공용화 형태 — `WorkflowSchemeScope` 일반화 vs shared-kernel 신설 (ADR 대상)
- [x] ~~`WorkflowKeyResolver` 폭발 반경~~ — 실측 해소. 이미 전 호출부가 `projectKey` 를 넘긴다
- [x] ~~프론트 프로젝트 어드민 가드 유무~~ — 실측 해소. 없다. 백엔드 403 + 안내 카드 관례를 따른다

## PR ① 마이그레이션 (T3 · project-workflow)

- [ ] 1-1 `workflows.project_id` + 부분 유니크 재편 (V206 소프트삭제 인덱스와 3조건 겹침 확인)
- [ ] 1-2 `workflow_schemes.project_id` + 동형 재편 (V201 `ix_workflow_schemes_key_active` 확인)
- [ ] 1-3 jOOQ 재생성 + `init_codegen.sql` 미러
- [ ] 1-4 마이그레이션 검증 테스트 — 부분 유니크가 **실제로 무는지** (red 먼저)
- [ ] 게이트 — 시드 4건 전부 `project_id IS NULL` · 재실행 멱등

## PR ② 스코프 결정 + CRUD 판정 (T2 · project-workflow)

- [ ] 2-1 도메인 `projectId: UUID?` (기본값 금지 — 컴파일 에러로 호출부 전수 방문)
- [ ] 2-2 `WorkflowSchemeController` 7곳 `Global` 하드코딩 제거 → 스코프 결정
- [ ] 2-2b `duplicate` 가 `projectKey` 를 받아 사본에 싣는다 (Jira 권장 우회 = 주 사용 경로)
- [ ] 2-3 목록 필터 (`listAssignableSchemes:172` 도 같이)
- [ ] 2-4 스코프 결정 전수 판별식 + 비-공허 짝
- [ ] 2-5 키 단독 조회 **동결** 판별식 + 비-공허 짝

## PR ③ 워크플로우 정의 권한 (T2 · identity-access · 보안)

- [ ] 3-1 `WorkflowDefinitionPermissionResolver` 스코프 도입
- [ ] 3-2 `DELETE` 는 SYSTEM_ADMIN 유지
- [ ] 3-3 권한 매트릭스 ↔ 엔드포인트 짝 판별식 + 비-공허 짝
- [ ] 게이트 — 스펙 §4 D6 표 4행 각각 red-first

## PR ④ 프로젝트 설정 UI (T1 · apps/web)

- [ ] 4-1 라우트 2종 (`requireAuthAndPasswordChanged` 승계)
- [ ] 4-2 `WorkflowEditorPage` 그대로 마운트 — **편집기 diff 0 줄**
- [ ] 4-3 스킴 생성·편집을 프로젝트 설정으로
- [ ] 4-4 `SETTINGS_LINKS` 12→13 (주석의 개수도 같이)
- [ ] 4-5 E2E — 어드민 전 경로 + 비-어드민 403 화면

## 공통 게이트 (매 PR)

- [ ] TDD red 를 **눈으로 봤다** (복사한 단언은 red 를 안 낼 수 있다)
- [ ] 뮤테이션 — 판정을 끊어 red 1회
- [ ] 프로젝트 소유 픽스처로 신규 경로를 실제로 태웠다 (시드 4건은 전부 전역이라 안 탄다)
- [ ] `pnpm verify` / `./gradlew test ktlintCheck detekt`
- [ ] `pnpm test:workflow` 판별식 전량
- [ ] worktree 훅이 **실제로 돌았는지 직접 확인** (침묵 무력화 함정)
