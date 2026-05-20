# scripts/workflow

BTS 워크플로우 보조 스크립트.

> Node 24+ (native TS 지원, Node 22+ `--experimental-strip-types` 호환).
> 외부 의존성 0. `node_modules` 불필요.

## classify-task.ts

자연어 입력을 작업 타입 / sub-agent / 바운디드 컨텍스트 / 슬러그로 분해.

### 사용

```bash
# 일회성
node scripts/workflow/classify-task.ts --title "코멘트 멘션 알림 추가해줘"

# 캐시 모드 (.bts-cache/classify.json에 저장, 같은 title이면 재사용)
node scripts/workflow/classify-task.ts --title "<...>" --cache

# package.json script
npm run classify -- --title "<...>" --cache
```

### 출력 예시

```json
{
  "title": "코멘트 멘션 알림 기능 추가해줘",
  "slug": "코멘트-멘션-알림-기능-추가해줘",
  "type": "feature",
  "agent": "backend-engineer",
  "primary_bc": "notification",
  "task_count": 0,
  "cached_at": "2026-05-19T13:01:11.945Z"
}
```

### 분류 우선순위

1. **auth strong** (2fa, totp, saml, oauth, oidc, ldap, csrf, webauthn) — 보안 영역은 fast-track 무시
2. **migration** (flyway, 마이그레이션, V<N>__ 패턴)
3. **chore prefix** (`chore:`, `docs:`, `style:`, `test:`, `perf:`) — fast-track
4. **bugfix prefix** (`fix:`, `refactor:`) — fast-track
5. **design** (디자인, 목업, 시안, 와이어프레임)
6. **qa** (e2e, playwright, vitest, testcontainers)
7. **api** (엔드포인트, REST, /api/)
8. **ui** (페이지, 컴포넌트, *.tsx)
9. **auth weak** (인증, 권한, 세션 등 광의)
10. **feature** (만들어줘, 추가, 새 기능)
11. **backend** (기본값)

### agent 매핑

| type | agent |
|---|---|
| `auth` | `security-engineer` |
| `migration` | `db-engineer` |
| `ui` | `frontend-engineer` |
| `design` | `designer` |
| `qa` | `qa-engineer` |
| `backend` / `api` / `feature` / `bugfix` / `chore` | `backend-engineer` |

### primary_bc 매핑

각 BC별 키워드 합산 점수가 가장 높은 것을 선택. 점수 0이면.

- `auth` 타입 → `identity-access` (보안 영역 특수 fallback)
- 그 외 → `null` (강제 매핑 금지. 약한 신호로 잘못된 BC에 떨어지는 amplification 방지 — 2026-05-20 회귀)
- `migration` / `qa` / `design` / `chore` → `null` (BC 무관 타입)

각 BC는 자기 이름 자체(`identity-access`, `issue-tracking` 등)를 키워드로 가진다. 입력에 BC 이름이 직접 명시되면(`identity-access §1 AuthN PoC`) 즉시 해당 BC로 매핑.

## 테스트

```bash
node --test scripts/workflow/*.test.ts
# 또는
npm run test:workflow
```

Node 내장 test runner 사용. Vitest/Jest 불필요.

## 캐시

`.bts-cache/classify.json` 1시간 TTL. `--cache` 플래그 줄 때만 읽고 씀. `.gitignore`에 등록되어 있어 커밋되지 않음.

캐시 무시하고 재계산하려면 플래그 없이 실행하거나 `.bts-cache/classify.json` 삭제.

## 향후 추가 예정

- `sync-obsidian.ts` (Phase 1) — post-merge hook에서 호출, Repo → Obsidian 단방향 동기화
- `session-context.ts` (Phase 1) — 세션 시작 시 진행 중 PR, 최근 머지, learnings 5건 출력 (AIG 패턴)
- `plan-sync.ts` (필요 시) — plan 파일 docs/plans ↔ Obsidian 동기화 (현재는 PR 자체에 포함하므로 불필요)
