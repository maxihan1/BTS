# CLAUDE.md

> 진입점. 절대 규칙 `DEVELOPMENT.md` · 데이터 `DATA.md`. 나머지는 아래 표의 정본을 가리킨다.

## 제품

**BTS (Project Atlas)** — 사내 1,000명 협업 워크스페이스. Atlas Issues + Atlas Wiki(v0.5).
Kotlin/Spring + React 19 · Naver Cloud 단일 호스트 · Maxi 1인 + Claude Code.
backend 9 BC + 배포 조립 `app` · 프론트 `apps/web` SPA.
**진척** — 146 FR 중 145 D 단계 완료(FR-WF-08 미착수). **D 단계 완료는 BC 완료 선언이 아니다** — §NFR 측정이 공통 관문이다. 정본 `docs/plan/README.md`.

## 작업

- 무언가를 만들거나 고치면 `/bts`(3단계). 질문 · 코드 설명은 그냥 답한다.
- **BTS 를 고치는 session 은 BTS 에서 연다.** 다른 repo 에서 시작한 session 에는 이 파일도 `/bts` 도 읽히지 않는다. 두 repo 를 같이 고치면 repo 마다 session 하나.
- 위험 경로(마이그레이션 · 보안 · shared-kernel · 모듈 토폴로지)만 시작 질문과 리뷰가 붙는다. 판정은 `/bts` 1단계.

## 테스트

- 작업 중에는 **바꾼 코드의 대상 테스트만** 돌린다 — `./gradlew :modules:<bc>:test --tests '<클래스>'` · `vitest run <파일>`.
- 전체 범위는 merge 직전 `select-test-scope.ts --run` **한 번**. 그 `EXIT=0` 이 merge 근거다 — CI 는 없다.
- 모듈 전체 · 인자 없는 `vitest run` · `pnpm verify` · Playwright 는 user scope hook 이 막는다. 막히면 우회하지 말고 대상을 좁힌다.
- E2E 는 쓰지도 돌리지도 않는다(2026-10-20~). UI 는 브라우저로 눈확인한다.

## 절대 규칙

- 한 PR = 한 BC. 다른 BC 는 pgmq(PostgreSQL 큐) 이벤트로만 — 직접 import 금지.
- 자동 생성 파일(`자동 생성` 주석)은 고치지 않는다. 원본을 고치고 생성기를 다시 돌린다.
- FR 추가 · 삭제 · 범위 변경 · 스펙 deviation 은 같은 PR 에서 `docs/rules/fr-sync-checklist.md` 를 전부 맞추고 `bash scripts/verify-master-plan.sh` 가 0 이어야 한다.
- Obsidian 은 단방향 — repo → `Maxi_wiki/BTS/`. 역방향 금지.
- 모르면 추측하지 말고 Maxi 에게 묻는다. 보안 의심 · 절대 규칙 충돌이면 멈춘다.

## 함정

- worktree 는 husky 훅을 **침묵 무력화**하고 `node_modules` 를 못 찾는다 — `/bts` 1단계의 symlink 3줄을 빼먹지 않는다.
- worktree lint-staged 가 옆 작업 미커밋 파일을 훔친다. `--only` 로 좁힌다.
- 지시문에 **개수를 쓰지 마라**. 「N건」은 눈가리개 — 전수 열거만 시킨다.
- 그 밖. `docs/rules/traps.md`

## 명령어

```bash
docker-compose -f infra/docker-compose.dev.yml up postgres redis minio
(cd backend && ./gradlew :modules:<bc>:test --tests '<클래스>')              # 대상 테스트
(cd apps/web && node_modules/.bin/vitest run <파일>)                       # 대상 테스트
node --experimental-strip-types scripts/workflow/select-test-scope.ts --run  # merge 직전 관문
node scripts/build-doc-index.mjs [--check]                                 # 문서·메모리 인덱스 · pre-commit 이 --check
bash scripts/verify-master-plan.sh                                         # FR 정합 (EXIT 4 차단)
```

## 문서

| 상황 | 문서 |
|---|---|
| 절대 규칙 · 코드 스타일 | `DEVELOPMENT.md` |
| DB · 마이그레이션 · 트랜잭션 | `DATA.md` |
| 도메인 · 기술 결정 · 용어 | `docs/sdd/README.md` · `Maxi_wiki/BTS/glossary.md` |
| UI/UX 기준 (Jira 패리티) | `docs/design/jira-parity-contract.md` |
| FR 진척 | `docs/plan/README.md` · `product/<bc>.md` · `docs/INDEX-fr.md`(grep 전용) |
| 표면 이름 · 차단 장치 | `docs/rules/behavior-rules.md` |

- 새 문서는 `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID. 생성기 규칙은 `docs/rules/doc-index.md`.
- 큰 파일은 통째로 Read 하지 않는다 — `grep -n '^#'` 뒤 부분 Read. INDEX 는 grep 전용.
- sub-agent 역할의 정본은 `.claude/agents/*.md`. 파일 없는 역할은 만들지 않는다.
