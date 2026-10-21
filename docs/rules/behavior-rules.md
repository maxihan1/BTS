<!-- 표면 표(위험 경로 판정의 이름 정본)와 실제로 막는 장치 목록 -->

# 표면 표와 차단 장치

## 1. 표면 표

글로브는 `scripts/workflow/surfaces.ts` 의 `SURFACES` 하나뿐이다. 이 표는 이름과 뜻만 갖는다.
`verify-master-plan.sh` 룰 J 가 두 목록의 차집합 0 과 개수 하한을 강제한다 (EXIT 4).
**위험** 표면을 건드리면 `/bts` 가 시작 질문과 리뷰를 붙인다. 판정은 `detect-tier.ts` 출력이다.

| 표면 키 | 뜻 | 티어 |
|---|---|---|
| `MIGRATION` | Flyway 마이그레이션 SQL | T3 · **위험** |
| `SHARED_KERNEL` | 공유 커널 모듈 (전 BC 역폐포) | T3 · **위험** |
| `TOPOLOGY` | 모듈 토폴로지 — `settings.gradle.kts` · 모듈 신설·삭제 | T3 · **위험** |
| `SEC_BE` | 백엔드 보안 — identity-access · 필드/이슈 권한 · OAuth | T2 (보안 렌즈) · **위험** |
| `SEC_FE` | 프론트 보안 — 인증 화면·권한 훅·API 클라이언트·라우터 | T2 (보안 렌즈) · **위험** |
| `API` | 공개 계약 — web · event · spi · port 패키지 | T2 |
| `DEPS` | 의존성 — 루트 gradle 4종 · `package.json` · lockfile | T2 |
| `BE_MAIN` | 백엔드 잔여 `src/main` | T2 |
| `GUARD_CI` | 가드·CI — 워크플로우 · 훅 · 판별식 · 생성기 스크립트 · 배포 게이트 · 호스트 노출면 | T2 |
| `SHELL` | 앱 셸 — 루트 라우트 · 관리자 라우트 | T1 (조건부 T2) |
| `FE_SRC` | 프론트 잔여 `apps/web/src` | T1 |
| `HARNESS` | 하네스 `.claude/**` | T1 |
| `TEST` | 테스트 코드 전량 | T1 · **승격 없음** |
| `STYLE_COPY` | 스타일·문구 — CSS · i18n | T0 |
| `DOC` | 문서 — `docs/**` · 루트 `*.md` · `*.html` | T0 |
| `.husky/pre-commit` — `build-doc-index.mjs --check` | 커밋 | **강등 금지.** 저장소 밖 메모리의 유일한 봉인 |
| `.husky/pre-commit` — lint-staged | 커밋 | worktree 가 훅을 침묵 무력화한 실측이 있다 — 로컬 훅은 **보조**다 |
| `scripts/verify-master-plan.sh` EXIT 3/4 | merge 전 (`bts` 4단계 · 스펙이 바뀐 PR) | FR 정합 · 카운트 drift · 표면 차집합(룰 J) · 줄수 규칙(룰 I) |

## 2. 실제로 막는 장치

| 장치 | 시점 | 막는 것 |
|---|---|---|
| `~/.claude/hooks/bts-test-guard.mjs` (user scope PreToolUse) | 명령 실행 전 | BTS 경로의 대상 없는 전체 테스트 · `pnpm verify` · Playwright |
| `.husky/pre-commit` — lint-staged · `build-doc-index.mjs --check` | 커밋 | 포맷 · 문서 인덱스 drift |
| `select-test-scope.ts --run` 의 EXIT | merge 직전 | 바뀐 범위의 테스트 실패 |
| `scripts/verify-master-plan.sh` EXIT 3/4 | FR · 스펙이 바뀐 PR | FR 정합 · 카운트 drift · 표면 차집합(룰 J) · 줄수 규칙(룰 I) |

CI 와 브랜치 보호는 없다. 이 표에 없는 규칙은 지침이다 — 지켜지지 않을 수 있다.

## 관련

- FR 변경 시 전수 동기화. [`fr-sync-checklist.md`](fr-sync-checklist.md)
- 문서 인덱스. [`doc-index.md`](doc-index.md)
- 영역별 코드 체크리스트. [`code-checklists.md`](code-checklists.md)
- 좁은 트리거 함정. [`traps.md`](traps.md)
