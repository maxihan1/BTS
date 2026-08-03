# 병렬 wave 환경 규약 — 공통 정본

> `/bts-impl`이 같은 wave 의 task 들을 **같은 worktree 위에서 병렬 dispatch** 할 때
> 모든 sub-agent 가 지키는 규약. **이 파일이 유일한 정본**이다 — 과거에 에이전트 정의
> 6곳에 복제돼 "수정 시 전수 동기화" 수동 규율에 의존하던 것을 단일화했다.
>
> **주입 방식**. bts-impl controller 가 이 파일을 세션당 1회 Read 하고 모든 dispatch
> prompt 에 본문을 인라인 주입한다. 에이전트 정의는 1줄 포인터만 둔다 (직접 Read 불필요).

## 공통 6조

같은 wave의 다른 task와 **같은 worktree를 공유**한다.

1. plan 메타 `files` 선언 파일만 수정. 선언 외 수정 필요 시 수정하지 말고 BLOCKED 보고
2. stage는 파일 단위 `git add <경로>`만 — `git add -A` / `git add .` / `git commit -a` 금지 (lint-staged race로 타 task 산출물 흡수, 동종 사고 3회)
3. 모듈/디렉토리 전체 포맷터 일괄 실행 금지 (`ktlintFormat` 등 — PRE_EXISTING 부수 변경 + 캐시 오염). 린트 검증은 check 계열만
4. 백그라운드 프로세스 잔류 금지 — dev 서버(5173 등)는 보고 전 종료 (worktree remove 후 5173 orphan이 이후 E2E webServer 타임아웃 유발, PR #41)
5. 스크래치/임시 파일은 보고 전 삭제. `git status --porcelain`으로 잔여물 확인
6. **보고 형식** — 아래 역할 변형을 따른다

## 6조 보고 형식 — 역할 변형

| 역할 | 보고 형식 |
|---|---|
| 구현 (backend·frontend·security·db) | STATUS + **RED/GREEN 각 commit hash 인용**, REFACTOR는 있으면 함께 (controller가 git log와 대조). 단, plan 이 ui 시각 검증 트랙을 지정한 task 는 bts-impl §타입별 규율의 ui 행 보고 형식을 따른다 |
| qa | STATUS(ADDED/SKIPPED 포함) + 추가/수정한 spec 파일 경로와 `test:` commit hash 인용 (구현 커밋이 없으므로 RED/GREEN/REFACTOR 3종 hash 는 비대상) |
| designer | STATUS + 산출물 경로 나열 (`docs/design/` · `apps/web/public/mockups/` · `DESIGN.md` 패치). 산출물 외 파일은 만들지 않는다 |
