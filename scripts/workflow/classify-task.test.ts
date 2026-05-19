// classify-task.ts의 단위 테스트 (Node 내장 test runner 사용)
import { test, describe } from 'node:test';
import { strict as assert } from 'node:assert';
import { classify, toSlug } from './classify-task.ts';

describe('toSlug', () => {
  test('한글 입력을 한 단어 단위로 보존하면서 kebab-case 변환', () => {
    const slug = toSlug('이슈에 멘션 알림 추가');
    assert.equal(slug, '이슈에-멘션-알림-추가');
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

  test('design → designer', () => {
    assert.equal(classify({ title: '대시보드 디자인 시안' }).agent, 'designer');
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
});
