# CLAUDE.md

> **헌법 §1 — 진입점.** 절대 규칙 `DEVELOPMENT.md` · 데이터 `DATA.md`. 나머지는 정본 포인터.

## 제품·도메인

**BTS (Project Atlas)** — 사내 1,000명 협업 워크스페이스. Atlas Issues + Atlas Wiki(v0.5).
Kotlin/Spring + React 19 · Naver Cloud 단일 호스트 · Maxi 1인 + Claude Code.
backend 10 Gradle 모듈 = 9 BC(identity-access issue-tracking project-workflow shared-kernel agile-planning
notification search-export-import slack-integration automation) + 배포 조립 `app` · 프론트 `apps/web` SPA.
**진척** — 143 FR 중 140 의 D 단계 완료 · FR-WF-05 완료(#398) · FR-WF-04 는 백엔드(D1~D5) 완료·UI(D6~D7) 미착수 · FR-WF-06~07 미착수. 정본 `docs/plan/README.md`.

## 작업 티어 — 바꾼 경로가 절차를 정한다

| 티어 | 표면 | 계획·테스트 | 리뷰·게이트 |
|---|---|---|---|
| T0 | 문서 CSS 문구 | 계획 0 · lint+타입 · 시각이면 눈확인 | 리뷰 1 · 게이트 2 |
| T1 | `apps/web/src` `.claude/**` 테스트 | 계획 0 · 재현 테스트 먼저 | 리뷰 1 · 게이트 2 |
| T2 | 보안경로 `backend/**/main` API 의존성 가드·CI | 계획 1파일 · TDD red-first | 리뷰 2 · 게이트 1+2 |
| T3 | 마이그레이션 `shared-kernel` 토폴로지 | ADR+plan · TDD + 마이그레이션 검증 | 리뷰 2+ceo · 게이트 1+2 |

① 섞이면 **최고 티어** ② 기본 T1, Maxi 지정 우선 ③ 미분류는 T1 + `UNMAPPED: <경로>` 를 게이트 2 요약에.
보안 표면은 T2 미만 불가. 글로브 정본 `scripts/workflow/surfaces.ts` · 전문 `docs/rules/behavior-rules.md`.

## 코드 지도

```
backend/modules/<bc>/src/{main,test}/  # 9 BC + app · gradlew 는 backend/
apps/web/src/ apps/web/e2e/  # React 19 SPA · Playwright
scripts/workflow/  # surfaces.ts · classify-task.ts · 판별식 (Node 22+)
scripts/doc-index/  # 문서·메모리 인덱스 생성기
.claude/skills/bts*/ · .claude/agents/  # 스킬 체인 · sub-agent
Maxi_wiki/BTS/  # Obsidian 단방향 미러 (저장소 밖)
```

## 명령어

```bash
# backend/
./gradlew test ktlintCheck detekt  # Testcontainers 포함
./gradlew :modules:<bc>:test  # 한 BC 만
# 루트
pnpm verify  # lint+typecheck+test+build
pnpm --filter web test:e2e  # Playwright
pnpm test:workflow  # 판별식 — CI 와 같은 목록
node scripts/build-doc-index.mjs [--check]  # 인덱스 · pre-commit
node scripts/build-dashboard.mjs  # progress.html
bash scripts/verify-master-plan.sh  # 정본 정합 (EXIT 4 차단)
bash scripts/doc-index/mutation-probe.sh  # 비-공허 확인
docker-compose -f infra/docker-compose.dev.yml up postgres redis minio
```

## 함정

- worktree 가 husky 훅을 **침묵 무력화**. 실패가 아니라 부재 — 돌았는지 직접 본다.
- worktree lint-staged 가 **옆 작업 미커밋 파일을 훔친다**. `--only` 로 좁힌다.
- worktree `node_modules` 는 심볼릭 — pnpm auto install 이 깨진다. `.bin` 부재부터 의심.
- 가드 수정 시 **표면을 없애면 판별자도 사라진다**. 일부러 끊어 red 1회 확인.
- 뮤테이션 검증은 **GREEN 선커밋 뒤**. 미커밋 원복은 소실이다.
- 지시문에 **개수를 쓰지 마라**. 「N건」은 눈가리개 — 전수 열거만 시킨다.

그 밖 6건. `docs/rules/traps.md`

## 핵심 패턴

- **TDD red→green→refactor** — 강도는 §작업 티어. T2+ 는 `test:` → `feat:` 순서가 대조된다.
- **worktree per 작업** — `.worktrees/<slug>` 안에서만 Edit/Write. 머지 후 정리.
- **BC 격리** — 한 PR = 한 BC. 다른 BC 는 pgmq(PostgreSQL 큐) 이벤트만, 직접 import 금지.
- **Obsidian 단방향** — Repo → `Maxi_wiki/BTS/`. 역방향 금지.

## 진입 트리

| 상황 | 문서 |
|---|---|
| 단순 질문 · 코드 설명 | 그냥 답변 (`/bts` 안 씀) |
| 작업 시작 · 단계 절차 | `.claude/skills/bts/SKILL.md` |
| 절대 규칙 · 코드 스타일 | `DEVELOPMENT.md` |
| DB · 마이그레이션 · 트랜잭션 | `DATA.md` |
| 도메인 비전 · 기술 결정 · 용어 | `docs/sdd/README.md` · `Maxi_wiki/BTS/glossary.md` |
| UI/UX 기준 (Jira 패리티) | `docs/design/jira-parity-contract.md` |
| 기능 진척 · FR 추적 | `docs/plan/README.md` · `product/<bc>.md` |
| FR 문서 · 최근 작업 | `docs/INDEX-fr.md` · `INDEX-recent.md` — **grep 전용** |

## 명세/범위 변경 시 전수 동기화

FR 추가·삭제 · 범위 변경 · 스펙 deviation 은 **같은 PR 에서** 전수 동기화. `docs/rules/fr-sync-checklist.md`

## sub-agent

역할·책임·금지의 정본은 `.claude/agents/*.md`. 파일 없는 역할은 만들지 않는다.

## 문서·인덱스

- **자동 생성 파일 수정 금지.** `자동 생성` 주석이 있으면 원본을 고치고 생성기 재실행.
- **새 문서** `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID. spec 과 plan 은 같은 slug.
- 생성기·frontmatter 키·새 디렉터리 절차. `docs/rules/doc-index.md`

## 컨텍스트 효율

- **한 번에 한 BC 만.** 여러 BC 동시 수정은 Maxi 확인.
- **대용량 파일 통째 Read 금지** — `grep -n '^#'` 뒤 부분 Read. INDEX 는 grep 전용.
- 긴 명세는 `docs/sdd/` 챕터 링크. **모르면 추측 말고 Maxi 에게 묻는다.**

## 비상시

- 빌드·테스트가 깨졌고 원인 모름 → `git status`·`git diff`·`gh pr list` 후 보고.
- 보안 의심 · 절대 규칙과 충돌 · 컨텍스트 부족 → **멈추고 Maxi 확인.** 자의 판단 금지.
