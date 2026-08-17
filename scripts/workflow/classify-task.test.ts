// classify-task.ts의 단위 테스트 (Node 내장 test runner 사용)
import { test, describe } from 'node:test';
import { strict as assert } from 'node:assert';
import { classify, toSlug } from './classify-task.ts';

describe('toSlug', () => {
  test('한글 only 입력은 ASCII fallback slug (task-<hash>)', () => {
    const slug = toSlug('이슈에 멘션 알림 추가');
    // 한국어만 있으면 ASCII fallback. 두 번 호출해도 같은 값 (결정론적).
    assert.match(slug, /^task-[a-f0-9]+$/, `fallback 형식 불일치: "${slug}"`);
    assert.equal(toSlug('이슈에 멘션 알림 추가'), slug);
  });

  test('영어 입력은 소문자 kebab-case', () => {
    assert.equal(toSlug('Add Mention Notification'), 'add-mention-notification');
  });

  test('Conventional Commit 접두사 제거', () => {
    assert.equal(toSlug('feat: add mention notify'), 'add-mention-notify');
    assert.equal(toSlug('fix: token leak'), 'token-leak');
    assert.equal(toSlug('chore(deps): bump kotlin'), 'bump-kotlin');
  });

  test('특수문자 제거 + 다중 공백 압축', () => {
    assert.equal(toSlug('hello,  world!!  foo'), 'hello-world-foo');
  });

  test('과도하게 긴 입력은 50자 컷', () => {
    const long = 'a'.repeat(100);
    assert.ok(toSlug(long).length <= 50);
  });
});

describe('classify — type 판정', () => {
  test('chore 접두사 → chore (fast-track)', () => {
    const r = classify({ title: 'chore: bump kotlin 1.9 → 2.0' });
    assert.equal(r.type, 'chore');
  });

  test('fix 접두사 → bugfix', () => {
    const r = classify({ title: 'fix: 이슈 키 redirect 누락' });
    assert.equal(r.type, 'bugfix');
  });

  test('auth 키워드 → auth', () => {
    assert.equal(classify({ title: '2FA TOTP 발급 흐름 추가' }).type, 'auth');
    assert.equal(classify({ title: 'SAML 로그인 지원' }).type, 'auth');
    assert.equal(classify({ title: 'CSRF 토큰 회전 정책' }).type, 'auth');
  });

  test('migration 키워드 → migration', () => {
    assert.equal(classify({ title: 'Flyway V20 — issues.search_vector 추가' }).type, 'migration');
    assert.equal(classify({ title: 'DB 마이그레이션 — 코멘트 인덱스' }).type, 'migration');
  });

  test('design 키워드 → design', () => {
    assert.equal(classify({ title: '대시보드 목업 시안' }).type, 'design');
    assert.equal(classify({ title: '디자인 시스템에 Pill 컴포넌트 추가' }).type, 'design');
  });

  test('qa 키워드 → qa', () => {
    assert.equal(classify({ title: 'E2E Playwright 시나리오 추가' }).type, 'qa');
  });

  test('ui 키워드 → ui', () => {
    assert.equal(classify({ title: '이슈 상세 페이지 코멘트 영역 개선' }).type, 'ui');
  });

  test('api 키워드 → api', () => {
    assert.equal(classify({ title: 'REST 엔드포인트 추가 — /api/v1/automation/rules' }).type, 'api');
  });

  test('feature 트리거 → feature', () => {
    assert.equal(classify({ title: '코멘트 멘션 알림 기능 추가해줘' }).type, 'feature');
    assert.equal(classify({ title: '새로운 자동화 룰 만들어줘' }).type, 'feature');
  });

  test('아무 신호 없음 → backend (기본값)', () => {
    assert.equal(classify({ title: 'IssueRepository 정리' }).type, 'backend');
  });

  test('보안 키워드 + Conventional prefix가 충돌하면 보안 우선', () => {
    // fix:는 bugfix지만, 본문이 auth면 auth가 더 적합 — 우선순위 결정 필요
    const r = classify({ title: 'fix: 2FA 검증 우회 가능' });
    // 보안 폭발 반경이 크므로 auth로 분류 (fast-track 적용 안 함)
    assert.equal(r.type, 'auth');
  });
});

describe('classify — agent 매핑', () => {
  test('auth → security-engineer', () => {
    assert.equal(classify({ title: '2FA TOTP 추가' }).agent, 'security-engineer');
  });

  test('migration → db-engineer', () => {
    assert.equal(classify({ title: 'Flyway 마이그레이션 V20' }).agent, 'db-engineer');
  });

  test('ui → frontend-engineer', () => {
    assert.equal(classify({ title: '이슈 상세 페이지 개선' }).agent, 'frontend-engineer');
  });

  test('design → frontend-engineer (designer 흡수)', () => {
    // 디자인 스펙만 쓰고 끊는 전용 에이전트를 없앴다. 스펙→구현이 한 에이전트 안에서 이어진다.
    assert.equal(classify({ title: '대시보드 디자인 시안' }).agent, 'frontend-engineer');
  });

  test('qa → qa-engineer', () => {
    assert.equal(classify({ title: 'E2E 시나리오 추가' }).agent, 'qa-engineer');
  });

  test('backend / api / feature / bugfix / chore → backend-engineer', () => {
    assert.equal(classify({ title: 'IssueRepository 정리' }).agent, 'backend-engineer');
    assert.equal(classify({ title: 'REST 엔드포인트 추가' }).agent, 'backend-engineer');
    assert.equal(classify({ title: '새 기능 만들어줘' }).agent, 'backend-engineer');
  });
});

describe('classify — primary_bc 매핑', () => {
  test('auth → identity-access', () => {
    assert.equal(classify({ title: '2FA TOTP 추가' }).primary_bc, 'identity-access');
  });

  test('워크플로우 키워드 → project-workflow', () => {
    assert.equal(classify({ title: '워크플로우 게이트 검증' }).primary_bc, 'project-workflow');
  });

  test('스프린트 키워드 → agile-planning', () => {
    assert.equal(classify({ title: '스프린트 번다운 차트' }).primary_bc, 'agile-planning');
  });

  test('자동화 키워드 → automation', () => {
    assert.equal(classify({ title: '자동화 룰 평가' }).primary_bc, 'automation');
  });

  test('알림 키워드 → notification', () => {
    assert.equal(classify({ title: '멘션 알림 추가' }).primary_bc, 'notification');
  });

  test('Slack 키워드 → slack-integration', () => {
    assert.equal(classify({ title: 'Slack Unfurl 추가' }).primary_bc, 'slack-integration');
  });

  test('이슈 키워드 → issue-tracking (기본 BC)', () => {
    assert.equal(classify({ title: '이슈 코멘트 첨부 개선' }).primary_bc, 'issue-tracking');
  });

  test('migration / qa / design은 primary_bc null', () => {
    assert.equal(classify({ title: 'Flyway V20' }).primary_bc, null);
    assert.equal(classify({ title: 'E2E 시나리오' }).primary_bc, null);
  });
});

describe('classify — 메타 필드', () => {
  test('slug 생성됨', () => {
    const r = classify({ title: '코멘트 멘션 알림 추가' });
    assert.ok(r.slug.length > 0);
    assert.ok(r.slug.includes('-') || r.slug.length < 5);
  });

  test('task_count 초기 0 (jq로 /bts-plan에서 머지됨)', () => {
    const r = classify({ title: '이슈 카드 디자인' });
    assert.equal(r.task_count, 0);
  });

  test('cached_at ISO timestamp', () => {
    const r = classify({ title: 'foo' });
    assert.ok(r.cached_at.match(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/));
  });

  test('tier 기본값 T1 · 지정하면 지정값 (판정 규칙 ②)', () => {
    // 착수 시점엔 diff 가 없어 경로로 잴 수 없다. 제목만 보고 티어를 추측하지 않는다 —
    // TaskType 축과 Tier 축은 직교라 'migration' 이라는 제목이 곧 T3 선언은 아니다.
    assert.equal(classify({ title: 'Flyway 마이그레이션 V21' }).tier, 'T1');
    assert.equal(classify({ title: 'IssueRepository 정리' }).tier, 'T1');
    assert.equal(classify({ title: 'Flyway 마이그레이션 V21', tier: 'T3' }).tier, 'T3');
  });
});

// ─────────────────────────────────────────────────────────
// 회귀 방지 (2026-05-20 분류기 버그)
// ─────────────────────────────────────────────────────────

describe('classify — auth 키워드 보강 (회귀 방지)', () => {
  test('AuthN / Spring Security / Argon2 / Keycloak → auth', () => {
    assert.equal(classify({ title: 'AuthN Provider 구조 추가' }).type, 'auth');
    assert.equal(classify({ title: 'Spring Security 필터 체인 설정' }).type, 'auth');
    assert.equal(classify({ title: 'Argon2 비밀번호 해싱 적용' }).type, 'auth');
    assert.equal(classify({ title: 'Keycloak 컨테이너 도입' }).type, 'auth');
  });

  test('Passkey / MFA → auth', () => {
    assert.equal(classify({ title: 'Passkey 등록 흐름' }).type, 'auth');
    assert.equal(classify({ title: 'MFA 강제 정책 추가' }).type, 'auth');
  });

  test('authentication / authn 영문 약어 → auth', () => {
    assert.equal(classify({ title: 'authentication provider plug 구조' }).type, 'auth');
    assert.equal(classify({ title: 'authn 인터페이스 설계' }).type, 'auth');
  });
});

describe('classify — primary_bc 보강 (회귀 방지)', () => {
  test('BC 이름이 입력에 직접 포함되면 해당 BC 매핑', () => {
    assert.equal(
      classify({ title: 'identity-access §1 AuthN PoC 시작' }).primary_bc,
      'identity-access'
    );
    assert.equal(
      classify({ title: 'agile-planning 보드 개선' }).primary_bc,
      'agile-planning'
    );
    assert.equal(
      classify({ title: 'slack-integration BC 진입' }).primary_bc,
      'slack-integration'
    );
  });

  test('Keycloak / Argon2 / authn 키워드 → identity-access', () => {
    assert.equal(classify({ title: 'Keycloak realm import' }).primary_bc, 'identity-access');
    assert.equal(
      classify({ title: 'Argon2 해싱 라이브러리 도입' }).primary_bc,
      'identity-access'
    );
    assert.equal(classify({ title: 'authn provider 인터페이스' }).primary_bc, 'identity-access');
  });

  test('BC 신호 0이면 fallback null (강제 issue-tracking 매핑 금지)', () => {
    // 어떤 BC 키워드와도 매치 안 되는 입력. issue-tracking으로 떨어지면 잘못.
    const r = classify({ title: 'Foo Bar 정리' });
    assert.equal(r.primary_bc, null);
  });
});

describe('classify — 원본 회귀 시나리오 (2026-05-20)', () => {
  test('AuthN PoC 원본 입력 — type/agent/primary_bc 모두 정확', () => {
    const r = classify({
      title:
        'identity-access §1 AuthN PoC 시작 — Spring Boot + Spring Security + Argon2 + Keycloak 컨테이너 (docs/plan/product/identity-access.md §1 기술검증 6항목)',
    });
    assert.equal(r.type, 'auth', `type가 ${r.type} (auth 기대)`);
    assert.equal(r.agent, 'security-engineer', `agent가 ${r.agent} (security-engineer 기대)`);
    assert.equal(
      r.primary_bc,
      'identity-access',
      `primary_bc가 ${r.primary_bc} (identity-access 기대)`
    );
  });
});

// ─────────────────────────────────────────────────────────
// Task 3 RED — slug ASCII 강제 + 50자 컷 (2026-05-20)
// ─────────────────────────────────────────────────────────

describe('toSlug — ASCII 강제 + 50자 컷 (Task 3)', () => {
  test('한국어 100자 입력은 50자 이하 ASCII-only slug 로 변환된다', () => {
    const title =
      'chore 정리 묶음 — PR #4 잔여 (escapeForLdapFilter 공백, INSERT...RETURNING, classify-task slug 50자컷) + Obsidian 동기화';
    const { slug } = classify({ title });
    assert.ok(slug.length <= 50, `slug 길이 ${slug.length} > 50: "${slug}"`);
    assert.match(slug, /^[a-z0-9-]+$/, `ASCII-only 아님: "${slug}"`);
  });

  test('한국어 음절 중간에서 컷팅하지 않는다 (UTF-16 surrogate 안전)', () => {
    const title = '가나다라마바사아자차카타파하';
    const { slug } = classify({ title });
    assert.doesNotMatch(slug, /[\uD800-\uDFFF]/, `lone surrogate 포함: "${slug}"`);
  });
});

// ─────────────────────────────────────────────────────────
// 부채 매핑 44 — 경로 신호가 뒤 슬래시를 요구해 apps/web 을 놓친다
// ─────────────────────────────────────────────────────────

describe('classify — 경로 신호의 단어 경계 (부채 44)', () => {
  test('apps/web 는 뒤 슬래시가 없어도 ui 로 간다 (S1)', () => {
    const r = classify({ title: 'apps/web 판별식 정리' });
    assert.equal(r.type, 'ui');
    assert.equal(r.agent, 'frontend-engineer');
  });

  test('apps/web/ 는 종전대로 ui 다 (회귀 방지)', () => {
    assert.equal(classify({ title: 'apps/web/ 판별식 정리' }).type, 'ui');
  });

  // ★오탐 방지. 장부가 남긴 후보 `/apps\/web\b/` 는 여기서 죽는다 —
  //   `\b` 는 `b` 다음 `-` 에서 성립해 apps/web-legacy 를 ui 로 끌어간다.
  test('apps/web 로 시작하는 더 긴 이름은 ui 가 아니다 (E1·E2)', () => {
    for (const title of ['apps/webhook 재시도 정리', 'apps/web-legacy 정리']) {
      assert.notEqual(classify({ title }).type, 'ui', title);
    }
  });
});

// ─────────────────────────────────────────────────────────
// 부채 매핑 33 — qa 판정이 ui 보다 앞서 혼합 PR 을 qa-engineer 로 보낸다
// ─────────────────────────────────────────────────────────

describe('classify — qa 는 경로가 앞, 키워드가 뒤 (부채 33)', () => {
  test('E2E 와 UI 신호가 섞이면 구현 가능한 에이전트로 간다 (S2)', () => {
    const r = classify({ title: '로딩 프레임 계약 E2E 신설 + apps/web/ 4파일' });
    assert.equal(r.type, 'ui');
    // qa-engineer 는 구현 코드 수정이 금지돼 있어 오배정되면 복구 불가다.
    assert.notEqual(r.agent, 'qa-engineer');
  });

  test('순수 E2E 작업은 여전히 qa 다 (S3 역방향 회귀)', () => {
    assert.equal(classify({ title: 'E2E 시나리오만 추가' }).type, 'qa');
    assert.equal(classify({ title: 'Playwright 회귀 보강' }).type, 'qa');
  });

  // ★★Task 1 과의 상호작용. `apps/web` 은 `apps/web/e2e/` 의 **접두사**라,
  //   경로 확장(Task 1)과 qa 를 통째로 뒤로 미는 설계가 겹치면
  //   Playwright 표면 전체가 qa-engineer 에 도달 불가가 된다. 이 단언이 그 설계를 배제한다.
  test('E2E 경로는 ui 경로보다 앞선다 (E14)', () => {
    const r = classify({ title: 'apps/web/e2e/issue-detail.spec.ts 회귀 보강' });
    assert.equal(r.type, 'qa');
    assert.equal(r.agent, 'qa-engineer');
  });

  test('E2E 경로도 뒤 슬래시를 요구하지 않는다 (E15 · 부채 44 와 같은 결함)', () => {
    assert.equal(classify({ title: 'apps/web/e2e 시나리오 추가' }).type, 'qa');
  });
});
