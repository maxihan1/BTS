# CLAUDE.md

> **이 파일은 Claude Code가 매 세션 시작 시 자동 로드한다.**
> **헌법의 §1 — 진입점.** 절대 규칙/스타일은 `DEVELOPMENT.md`, 데이터 규칙은 `DATA.md`.

## 프로젝트 한 줄

**BTS (Project Atlas)** — 사내 1,000명 규모 협업 워크스페이스. Atlas Issues (이슈 트래커) + Atlas Wiki (v0.5 예정). Kotlin/Spring + React 19, Naver Cloud Docker Compose 단일 호스트. Maxi 1인 + Claude Code 개발 모델.

**현재 단계**. SDD v0.5.0 작성 완료, **Phase 0 PoC 진입 직전** (코드 0줄). 아래 명령어는 PoC 단계에서 도입 예정.

## 진입 트리 — 어떤 상황에 어느 문서

| 상황 | 봐야 할 문서 |
|---|---|
| 워크플로우 / 작업 시작 | 이 파일 §워크플로우 + `.claude/skills/bts/SKILL.md` (작성 예정) |
| 절대 규칙 18개 / 코드 스타일 | `DEVELOPMENT.md` |
| DB / 마이그레이션 / 트랜잭션 / 이슈키 영속성 | `DATA.md` |
| 도메인 비전 / 기술 결정 / 26개 챕터 | `docs/sdd/README.md` |
| 도메인 용어 사전 (DDD) | `Maxi_wiki/BTS/glossary.md` (Obsidian) |
| 과거 사고/교훈 (회귀 방지) | `Maxi_wiki/BTS/learnings.md` (Obsidian) |
| 바운디드 컨텍스트별 노트 | `Maxi_wiki/BTS/domain/<bc>.md` |

## 워크플로우 — `/bts` 단일 진입점 (6단계)

```
/bts <자연어 요청>
   ↓ [자동 선행 읽기] _index + history(최근10) + learnings(최근5)
[1] /bts-start       → classify + git worktree add .worktrees/<slug> + Draft PR
[2] /bts-domain      → grill-with-docs (도메인 용어 정제, CONTEXT.md/ADR)
[3] /bts-spec        → office-hours (스펙 + 디자인 결정 통합)
[4] /bts-plan        → writing-plans (TDD task 분해)
[5] /bts-review-plan → plan-ceo + plan-design + plan-eng 순차
   ↓ 🛑 게이트 1 — Maxi 검토 (도메인/스펙/계획 일괄)
[6] /bts-impl        → subagent-driven-development + TDD 강제 (red→green→refactor)
                        sub-agent: security / backend / frontend / designer / db / qa
[7] /bts-codereview  → code-reviewer agent (PR 단위, 절대 규칙 18개 인라인)
   ↓ 🛑 게이트 2 — Maxi 검토 (BLOCKER)
[자동] verify → merge → worktree 정리 + sync-obsidian (history/decisions append)
```

자세히. `.claude/skills/bts-workflow/SKILL.md` (작성 예정).

## sub-agent 6종

| 에이전트 | 담당 영역 |
|---|---|
| `security-engineer` | 인증/2FA/SSO/권한/CSRF/암호화 (`identity-access`) |
| `backend-engineer` | Kotlin/Spring 일반 (이슈/워크플로우/자동화/알림 BC) |
| `frontend-engineer` | React 19 / TanStack / TipTap |
| `designer` | shadcn/Radix 스펙, 목업, DESIGN.md (Phase 1+) |
| `db-engineer` | Flyway / jOOQ / 스키마 / 마이그레이션 |
| `qa-engineer` | Playwright E2E + Testcontainers + 커버리지 감사 |

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
