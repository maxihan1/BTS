# CLAUDE.md

> **이 파일은 Claude Code가 매 세션 시작 시 자동 로드한다.**
> **헌법의 §1 — 진입점.** 절대 규칙/스타일은 `DEVELOPMENT.md`, 데이터 규칙은 `DATA.md`.

## 프로젝트 한 줄

**BTS (Project Atlas)** — 사내 1,000명 규모 협업 워크스페이스. Atlas Issues (이슈 트래커) + Atlas Wiki (v0.5 예정). Kotlin/Spring + React 19, Naver Cloud Docker Compose 단일 호스트. Maxi 1인 + Claude Code 개발 모델.

**현재 단계**. SDD v0.5.0 + 하네스 작성 완료, **Phase 0 PoC 진입 직전** (코드 0줄).

- 오늘 동작. `/bts` 워크플로우 스킬 체인, `scripts/workflow/*` (Node 22+ 필요)
- PoC 도입 후 동작. Gradle / pnpm / Docker Compose 명령 (아래 §자주 쓰는 명령어)

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

## 디렉토리 (한눈에)

```
BTS/
├── CLAUDE.md / DEVELOPMENT.md / DATA.md   # 헌법 3종
├── docs/sdd/                              # 설계 문서 26개 챕터
├── .claude/skills/bts-*/SKILL.md          # 워크플로우 스킬 9개
├── .claude/agents/*-engineer.md           # sub-agent 6개
├── .claude/settings.json                  # 권한 (Bash deny 6종)
├── scripts/workflow/                      # classify-task.ts (Phase 0 동작)
└── (backend/, apps/web/, packages/)       # Phase 0 PoC 진입 후 생성
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

## 자주 쓰는 명령어 (PoC 도입 후 적용)

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

## 비상시

- 빌드/테스트 깨졌고 원인 모름 → `git status`, `git diff`, `gh pr list` 확인 후 Maxi 보고
- 보안 의심 코드 발견 → 즉시 Maxi 보고, 변경 중단
- Maxi 지시가 절대 규칙과 충돌 → 충돌 명시 후 확인 요청 (자의 판단 금지)
- 컨텍스트 부족해서 자신 없음 → 추측 말고 질문
