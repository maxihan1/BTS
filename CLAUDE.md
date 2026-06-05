# CLAUDE.md

> **이 파일은 Claude Code가 매 세션 시작 시 자동 로드한다.**
> **헌법의 §1 — 진입점.** 절대 규칙/스타일은 `DEVELOPMENT.md`, 데이터 규칙은 `DATA.md`.

## 프로젝트 한 줄

**BTS (Project Atlas)** — 사내 1,000명 규모 협업 워크스페이스. Atlas Issues (이슈 트래커) + Atlas Wiki (v0.5 예정). Kotlin/Spring + React 19, Naver Cloud Docker Compose 단일 호스트. Maxi 1인 + Claude Code 개발 모델.

**현재 단계**. **Phase 1 진행 중** (121 FR, 진척 현황은 `docs/plan/progress.html` / `docs/plan/README.md`). backend 4개 BC(identity-access · issue-tracking · project-workflow · shared-kernel) + `apps/web` SPA 구현 중. SDD v0.5.0.

- 워크플로우. `/bts` 스킬 체인, `scripts/workflow/*` (Node 22+ 필요)
- 빌드/실행. Gradle / pnpm / Docker Compose — 이미 적용 중 (아래 §자주 쓰는 명령어)

## 작업 기준 — 완제품

**모든 작업은 완제품(production) 기준으로 진행한다.** PoC / 프로토타입 / 임시 코드 금지.

- 단계 표기(Phase 0 / Phase 1)는 **도입 시점**의 표시일 뿐, 작업 **품질 수준**이 아니다. Phase 0에 작성하는 코드도 완제품 품질을 충족해야 한다.
- "일단 동작만 하게" / "나중에 리팩토링" / "PoC라서 생략" 같은 사고 금지. 작성 시점에 절대 규칙 (`DEVELOPMENT.md §1`) · 테스트 · 보안 · 에러 처리 모두 만족해야 한다.
- Maxi가 "PoC 수준으로" 라고 명시한 경우에만 예외. 그 외는 모든 코드가 production-ready.
- 에이전트(sub-agent 포함)가 이 작업을 PoC로 오인하지 않도록, 새 코드/문서/스킬 작성 시 PoC·prototype 같은 단어 사용 금지.

## 진입 트리 — 어떤 상황에 어느 문서

| 상황 | 봐야 할 문서 |
|---|---|
| 단순 질문 / 코드 설명 | 그냥 답변 (`/bts` 진입 안 함) |
| 워크플로우 / 작업 시작 | 이 파일 §워크플로우 + `.claude/skills/bts/SKILL.md` |
| 절대 규칙 18개 / 코드 스타일 | `DEVELOPMENT.md` |
| DB / 마이그레이션 / 트랜잭션 / 이슈키 영속성 | `DATA.md` |
| 도메인 비전 / 기술 결정 / 26개 챕터 | `docs/sdd/README.md` |
| 도메인 용어 사전 (DDD) | `Maxi_wiki/BTS/glossary.md` (Obsidian) |
| 과거 사고/교훈 (회귀 방지) | `Maxi_wiki/BTS/learnings.md` (Obsidian) |
| 바운디드 컨텍스트별 노트 | `Maxi_wiki/BTS/domain/<bc>.md` |
| 기능 구현 진척 / FR 추적 | `docs/plan/README.md` (BC별 product/*.md, 121 FR) |

## 디렉토리 (한눈에)

```
BTS/
├── CLAUDE.md / DEVELOPMENT.md / DATA.md   # 헌법 3종
├── docs/sdd/                              # 설계 문서 26개 챕터
├── .claude/skills/bts-*/SKILL.md          # 워크플로우 스킬 9개
├── .claude/agents/*-engineer.md           # sub-agent 6개
├── .claude/settings.json                  # 권한 (Bash deny 6종)
├── scripts/workflow/                      # classify-task.ts 등 (Node 22+)
├── backend/modules/                        # 4개 BC (identity-access·issue-tracking·project-workflow·shared-kernel)
└── apps/web/                               # React 19 단일 SPA (packages/ 모노레포 분할 없음)
Maxi_wiki/BTS/                             # Obsidian (외부, 단방향 미러)
```

## 워크플로우 — `/bts` 단일 진입점

`/bts <자연어>` → start → domain → spec → plan → review-plan → 🛑게이트1 → impl → codereview → 🛑게이트2 → merge

자세한 다이어그램 + 단계별 절차. `.claude/skills/bts-workflow/SKILL.md`.

## 핵심 패턴 (BTS만의)

- **TDD red→green→refactor 강제** — `/bts-impl`이 git log에서 `test:` 커밋이 `feat:` 커밋보다 먼저인지 자동 검증. 위반 시 BLOCKED → implementer 재dispatch
- **git worktree per 작업** — `.worktrees/<slug>` 안에서만 Edit/Write. main 트리 오염 차단. 머지 후 자동 정리
- **BC 격리** — 한 PR = 한 바운디드 컨텍스트. 다른 BC 호출은 이벤트 발행만 (pgmq), 직접 import 금지
- **Obsidian 단방향** — Repo → `Maxi_wiki/BTS/` 미러. Phase 0은 수동, Phase 1에 자동화

## sub-agent 6종

`security` (인증/권한) / `backend` (Kotlin 일반) / `frontend` (React) / `designer` (스펙만) / `db` (Flyway/jOOQ) / `qa` (E2E)

각 책임/금지/참조. `.claude/agents/<role>-engineer.md`.

## 자주 쓰는 명령어

```bash
# 백엔드 (Gradle)
./gradlew test                      # 단위 + 통합 (Testcontainers)
./gradlew :backend:bootRun          # 로컬 실행
./gradlew flywayMigrate             # DB 마이그레이션
./gradlew ktlintCheck detekt        # 린트 + 정적 분석

# 프론트엔드 (pnpm 모노레포)
pnpm dev / test / test:e2e / lint / typecheck
pnpm verify                         # 통합 (lint + typecheck + test + build)

# 인프라
docker-compose -f infra/docker-compose.dev.yml up postgres redis minio
```

## 컨텍스트 효율

- **한 번에 한 BC만** 작업. 여러 BC 동시 수정은 Maxi 확인.
- 긴 명세는 `docs/sdd/` 챕터 링크. 본문 복사 금지.
- Skills/agent의 트리거 조건이 맞으면 자동 활성화. 임의 호출 금지.
- **모르겠으면 Maxi에게 물어보기.** 추측 구현 금지.

## 사용자 커뮤니케이션 스타일

- **개발 비전문자 기준으로 설명**. Maxi는 깊은 개발 지식이 없음. 도메인/코드 용어 첫 등장 시 한 줄 비유/풀이 동반. 예. "worktree (같은 책상에 여러 작업 가지를 동시에 펼쳐놓는 Git 기능)".
- **무엇 + 왜 같이**. "라인 205 삭제" 보다 "공백을 잘못 처리하는 한 줄을 지워서 정상 사용자명이 통과하게" 형태로.
- **약어/영어 풀이**. PR (Pull Request — 코드 변경 제안), BC (Bounded Context — 책임 범위로 나눈 도메인 단위), ADR (Architecture Decision Record — 기술 결정 기록) 등 첫 등장 시 한 번 풀이. Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리), Flyway (DB 스키마 변경을 버전 관리하는 도구), jOOQ (SQL을 코드로 안전하게 작성하는 라이브러리) 등 처음 보는 영어 도구명도 한 줄 소개.
- **BTS 고유 용어도 풀이 대상**. BC, worktree per 작업, wave 병렬 dispatch, TDD red→green→refactor, classify-task, pgmq (PostgreSQL 기반 메시지 큐) 등.
- **갈림길에서는 옵션 2~3개 제시**. 추측 구현 금지 (§컨텍스트 효율). 옵션마다 한 줄 trade-off.
- **콜론으로 문장 끝내지 않기**. 글로벌 `~/.claude/CLAUDE.md` §5 재확인. 리스트/예시 앞이어도 마침표로 끝낼 것.
- **글로벌 `~/.claude/CLAUDE.md` §Explanation Style과 일관**. BTS에서는 BTS 고유 용어 풀이를 추가로 강조.

## 비상시

- 빌드/테스트 깨졌고 원인 모름 → `git status`, `git diff`, `gh pr list` 확인 후 Maxi 보고
- 보안 의심 코드 발견 → 즉시 Maxi 보고, 변경 중단
- Maxi 지시가 절대 규칙과 충돌 → 충돌 명시 후 확인 요청 (자의 판단 금지)
- 컨텍스트 부족해서 자신 없음 → 추측 말고 질문
