# CLAUDE.md

> **이 파일은 Claude Code가 매 세션 시작 시 자동 로드한다.**
> **헌법의 §1 — 진입점.** 절대 규칙/스타일은 `DEVELOPMENT.md`, 데이터 규칙은 `DATA.md`.

## 프로젝트 한 줄

**BTS (Project Atlas)** — 사내 1,000명 규모 협업 워크스페이스. Atlas Issues (이슈 트래커) + Atlas Wiki (v0.5 예정). Kotlin/Spring + React 19, Naver Cloud Docker Compose 단일 호스트. Maxi 1인 + Claude Code 개발 모델.

**현재 단계**. **Phase 1 — 139 FR**. **FR-UX-10~14 5건이 미완**이고(FR-UX-09 는 B1 #328 · F2 #331 · F3 #333 으로 **완주**, FR-UX-10 은 F10 #336 으로 D1~D5 완료·D6/D7 은 F11 대기) 나머지 전량은 D1~D7 을 마쳤다 (2026-08-03 실측 `- [x] D«n».` 935건 / 미완 30건).
남은 것은 각 BC 의 **완료 게이트** — §NFR 측정표 · `CHANGELOG.md` 정리 · **Maxi 1인 선언**.
진척 정본은 `docs/plan/README.md` · `docs/progress.html`(`node scripts/build-dashboard.mjs` 재생성).

backend 10개 Gradle 모듈 = 9 BC(identity-access · issue-tracking · project-workflow · shared-kernel ·
agile-planning · notification · search-export-import · slack-integration · automation) + 배포 조립 `app`.
프론트는 `apps/web` 단일 SPA. SDD v0.5.0. 워크플로우는 `/bts` 스킬 체인 (Node 22+).
디렉토리·명령어. [`docs/rules/commands.md`](docs/rules/commands.md)

## 작업 기준 — 완제품

**모든 작업은 완제품(production) 기준으로 진행한다.** PoC / 프로토타입 / 임시 코드 금지.

- 단계 표기(Phase 0 / Phase 1)는 **도입 시점** 표시일 뿐 **품질 수준**이 아니다. "일단 동작만" / "나중에 리팩토링" / "PoC라서 생략" 금지. 작성 시점에 절대 규칙(`DEVELOPMENT.md §1`) · 테스트 · 보안 · 에러 처리를 모두 만족해야 한다.
- Maxi가 "PoC 수준으로"라고 **명시한 경우에만** 예외. 에이전트가 오인하지 않도록 새 코드/문서/스킬에 PoC·prototype 같은 단어를 쓰지 않는다.

## 명세/범위 변경 시 전수 동기화

**초기 기획과 달라지는 모든 변경(신규 기능 / 기능·범위 변경 / 스펙 deviation / FR 추가·삭제)은 같은 PR 안에서 영향받는 모든 정본·미러·카운트를 전수 동기화한다.** 일부만 고쳐 문서 간 drift를 남기지 않는다.

**동기화 대상 9종 체크리스트 + verify 강제 절차. [`docs/rules/fr-sync-checklist.md`](docs/rules/fr-sync-checklist.md)** (정본)

**강제**. 머지 전 `bash scripts/verify-master-plan.sh` 통과 필수 (종료 4 로 자동 차단).

## 진입 트리 — 어떤 상황에 어느 문서

| 상황 | 봐야 할 문서 |
|---|---|
| 단순 질문 / 코드 설명 | 그냥 답변 (`/bts` 진입 안 함) |
| 워크플로우 / 작업 시작 | 이 파일 §워크플로우 + `.claude/skills/bts/SKILL.md` |
| 절대 규칙 19개 / 코드 스타일 | `DEVELOPMENT.md` |
| DB / 마이그레이션 / 트랜잭션 / 이슈키 영속성 | `DATA.md` |
| 도메인 비전 / 기술 결정 / 24개 챕터 | `docs/sdd/README.md` |
| UI/UX 작업 기준 (Jira 패리티 계약) | `docs/design/jira-parity-contract.md` |
| 도메인 용어 사전 (DDD) | `Maxi_wiki/BTS/glossary.md` (Obsidian) |
| 과거 사고/교훈 (회귀 방지) | `Maxi_wiki/BTS/learnings.md` (Obsidian) |
| 바운디드 컨텍스트별 노트 | `Maxi_wiki/BTS/domain/<bc>.md` |
| 기능 구현 진척 / FR 추적 | `docs/plan/README.md` (BC별 product/*.md, 139 FR) |
| 이 FR 관련 문서 전부 / 최근 작업 | `docs/INDEX.md` → `INDEX-fr.md` · `INDEX-recent.md` |
| 명령어 / 디렉토리 구조 | `docs/rules/commands.md` |
| FR 변경 시 동기화 절차 | `docs/rules/fr-sync-checklist.md` |

## 워크플로우 — `/bts` 단일 진입점

`/bts <자연어>` → start → domain → spec → plan → review-plan → 🛑게이트1 → impl → codereview → 🛑게이트2 → merge

자세한 다이어그램 + 단계별 절차. `.claude/skills/bts/SKILL.md` · 참조 맵 [`docs/rules/workflow-map.md`](docs/rules/workflow-map.md).

## 핵심 패턴 (BTS만의)

- **TDD red→green→refactor 강제** — `/bts-impl`이 git log에서 `test:` 커밋이 `feat:` 커밋보다 먼저인지 자동 검증. 위반 시 BLOCKED → implementer 재dispatch. **예외: ui 시각 변경은 시각 검증 트랙** (red-first 면제, 기존 E2E 동반 실행 + 브라우저 눈확인 — `/bts-impl` §타입별 규율)
- **git worktree per 작업** — `.worktrees/<slug>` 안에서만 Edit/Write. main 트리 오염 차단. 머지 후 자동 정리
- **BC 격리** — 한 PR = 한 바운디드 컨텍스트. 다른 BC 호출은 이벤트 발행만 (pgmq), 직접 import 금지
- **Obsidian 단방향** — Repo → `Maxi_wiki/BTS/` 미러. Phase 0은 수동, Phase 1에 자동화

## sub-agent 6종

`security` (인증/권한) / `backend` (Kotlin 일반) / `frontend` (React) / `designer` (스펙만) / `db` (Flyway/jOOQ) / `qa` (E2E)

각 책임/금지/참조. `.claude/agents/<role>-engineer.md`.

## 문서 인덱싱 규칙

- **생성 파일 직접 수정 금지.** 상단에 `자동 생성` 주석이 있으면 원본을 고치고
  `node scripts/build-doc-index.mjs` 재실행. (`MEMORY.md` · `memory/index/*.md` · `docs/INDEX*.md`)
- **새 메모리.** frontmatter 필수 — `name` · `description` · `metadata.type` ·
  `metadata.hook`(≤80자) · `metadata.priority`(`critical`|`normal`) · `metadata.category`
- **새 문서.** 파일명 `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID 명시.
  spec 과 plan 은 **같은 slug**. 정본에 없는 FR ID 는 인덱스에서 제외되고 경고로 뜬다.
- **새 문서 디렉토리 추가 시** `scripts/doc-index/config.mjs` 의 `SOURCES` 와
  `workflow-scripts-ci.yml` 의 `paths`(pull_request·push 양쪽)를 **같은 PR 에서** 고친다.
  판별식 룰 L 이 차단한다.
- **어디를 먼저 보나.** FR 작업 → [`docs/INDEX-fr.md`](docs/INDEX-fr.md) ·
  최근 작업 → [`docs/INDEX-recent.md`](docs/INDEX-recent.md). **통째로 열지 말고 grep 한다.**

상세(생성기 구조·판별식 4종·비-공허 확인). [`docs/rules/doc-index.md`](docs/rules/doc-index.md)

## 컨텍스트 효율

- **한 번에 한 BC만** 작업. 여러 BC 동시 수정은 Maxi 확인.
- **대용량 3파일 통째 Read 금지** — `TODOS.md`(126KB) · `Maxi_wiki/BTS/learnings.md`(98KB) ·
  `DESIGN.md`(43KB)는 grep 또는 헤딩 인덱스(`grep -n '^#'`) 후 부분 Read.
  `docs/INDEX-fr.md`·`INDEX-recent.md`는 **grep 전용** — 줄당 수백 바이트라 부분 Read가 방어가 안 된다.
- 긴 명세는 `docs/sdd/` 챕터 링크. 본문 복사 금지.
- Skills/agent의 트리거 조건이 맞으면 자동 활성화. 임의 호출 금지.
- **모르겠으면 Maxi에게 물어보기.** 추측 구현 금지.

## 사용자 커뮤니케이션 스타일

- **글로벌 `~/.claude/CLAUDE.md` §Explanation Style·§5 준수 (정본)**. 비전문자 기준 설명, 무엇+왜 같이, 용어/약어 첫 등장 시 한 줄 풀이·비유, 콜론으로 문장 끝내지 않기. 상세 규칙은 글로벌 파일 참조.
- **작업 보고는 계층형 3블록 필수 (§Explanation Style Work-Report Format)**. 코드 변경·조사·디버깅·리뷰 결과를 Maxi에게 보고할 때는 `✅ 한 줄` → `💡 의미` → `🔧 기술 상세(안 봐도 됨)` 순서로 연다. 전문용어·약어는 `🔧` 아래로 격리하고 첫 등장 시 괄호 풀이. **MEMORY.md·docs·PR 제목·스킬 출력 예시가 압축 은어라도 사용자 응답은 그 밀도를 따라가지 않는다.** 게이트 요약(bts-codereview 게이트 2 등)도 예외 없음.
- **BTS 고유 용어·도구명도 풀이 대상**. BC (Bounded Context — 책임 범위로 나눈 도메인 단위), worktree per 작업, pgmq (PostgreSQL 기반 메시지 큐), Testcontainers, Flyway, jOOQ 등 첫 등장 시 한 줄 소개. 용어 사전은 `Maxi_wiki/BTS/glossary.md`.
- **갈림길에서는 옵션 2~3개 제시**. 추측 구현 금지 (§컨텍스트 효율). 옵션마다 한 줄 trade-off.

## 비상시

- 빌드/테스트 깨졌고 원인 모름 → `git status`, `git diff`, `gh pr list` 확인 후 Maxi 보고
- 보안 의심 코드 발견 → 즉시 Maxi 보고, 변경 중단
- Maxi 지시가 절대 규칙과 충돌 → 충돌 명시 후 확인 요청 (자의 판단 금지)
- 컨텍스트 부족해서 자신 없음 → 추측 말고 질문
