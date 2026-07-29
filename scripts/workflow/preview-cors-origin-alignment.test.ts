// vite 가 서빙하는 로컬 포트와 백엔드 CORS 허용 오리진의 정합을 강제하는 판별식
//
// 왜 이 테스트가 있나. `vite.config.ts` 의 포트는 **하드코딩 값**이고, 백엔드가 허용하는 오리진은
// `application.yml` 의 **다른 하드코딩 값**이다. 둘의 정합을 아무도 보지 않으면 조용히 갈라진다 —
// 이 저장소가 겪은 실패 양식 그대로다 (CORS allowedMethods ↔ 컨트롤러 매핑, BC_KEYWORDS ↔ 도메인
// 어휘, backend-ci 매트릭스 ↔ Gradle 모듈).
//
// 실제로 갈라졌다. #319 가 `vite preview` 를 「실 백엔드로 UI 를 손검증할 유일한 경로」로 열었으나
// preview 는 4173 에서 뜨고 CORS 허용 기본값은 5173 이었다. 브라우저는 **POST 에만** `Origin` 을
// 보내므로(GET 에는 안 보낸다) GET 은 전부 200 인데 로그인 POST 만 `403 Invalid CORS request` 가
// 됐다 — 손검증의 첫 단계부터 막혀 경로 자체가 못 쓰였고, GET 이 멀쩡해서 원인을 CORS 로
// 의심하기까지 시간이 걸렸다.
//
// 판별식. vite 가 서빙하는 **모든** 로컬 포트가 CORS 허용목록에 있는지 **차집합 0** 으로 본다.
// 포트를 한 번 맞추는 것으로는 부족하다 — 다시 갈라지는 것을 막는 눈이 있어야 한다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const VITE_CONFIG = path.join(REPO_ROOT, 'apps/web/vite.config.ts');
const APP_YML = path.join(REPO_ROOT, 'backend/modules/app/src/main/resources/application.yml');
const BACKEND_CI = path.join(REPO_ROOT, '.github/workflows/backend-ci.yml');
const PLAYWRIGHT_CONFIG = path.join(REPO_ROOT, 'apps/web/playwright.config.ts');

/**
 * vite 가 로컬에서 브라우저에 서빙하는 블록.
 *
 * 둘 다 `/api` 를 백엔드로 프록시하고, 브라우저는 그 요청에 `Origin` 을 붙인다 —
 * 즉 **양쪽 모두** CORS 허용목록에 있어야 한다. dev 만 보면 preview 가 빠진다.
 */
const VITE_SERVE_BLOCKS = ['server', 'preview'] as const;

/** `on:` 아래에서 판별식 입력을 걸어야 하는 트리거. 한쪽만 걸면 봉인이 절반만 닫힌다. */
const CI_TRIGGERS = ['pull_request', 'push'] as const;

/**
 * 이 판별식의 **입력** 파일 경로. 어느 쪽이 바뀌어도 판별식이 CI 에서 돌아야 한다.
 *
 * 판별식을 만들어도 CI 가 안 돌리면 로컬 1회성 확인으로 끝나고 썩는다
 * (`pnpm test:workflow` 를 어느 CI 도 돌리지 않던 2026-07-27 사고와 같은 양식).
 */
const REQUIRED_CI_TRIGGER_PATHS = [
  'backend/**', //               application.yml 의 CORS 허용목록
  'scripts/workflow/**', //      이 판별식 자신
  'apps/web/vite.config.ts', //  vite 의 로컬 포트
] as const;

/** 파서가 고장났을 때 차집합이 공허하게 통과하는 것을 막는 하한. */
const MIN_CORS_ORIGINS = 1;

interface ViteServeBlock {
  /** 이 블록이 바인딩하는 로컬 포트 */
  port: number;
  /** 포트 점유 시 조용히 다른 포트로 옮기지 않고 즉시 실패하는가 */
  strictPort: boolean;
}

/**
 * `vite.config.ts` 의 `server` · `preview` 블록에서 포트와 `strictPort` 를 수집한다.
 *
 * 값을 이 파일에 복사해 두면 그것이 **또 하나의 어긋날 수 있는 목록**이 되므로 원본을 읽는다.
 * @returns 블록 이름 → 파싱된 설정. 파싱 실패한 블록은 누락되고 양성 대조군 테스트가 잡는다.
 */
function viteServeBlocks(): Map<string, ViteServeBlock> {
  const src = fs.readFileSync(VITE_CONFIG, 'utf8');
  const blocks = new Map<string, ViteServeBlock>();

  for (const name of VITE_SERVE_BLOCKS) {
    // 블록 본문에 중첩 중괄호가 없다는 전제. 생기면 여기서 못 찾고 양성 대조군이 red 가 된다.
    const body = new RegExp(`\\n {2}${name}: \\{([^}]*)\\}`).exec(src)?.[1];
    const port = body === undefined ? undefined : /port:\s*(\d+)/.exec(body)?.[1];
    if (body === undefined || port === undefined) continue;

    blocks.set(name, { port: Number(port), strictPort: /strictPort:\s*true/.test(body) });
  }

  return blocks;
}

/** `vite.config.ts` 전체에 등장하는 `port: <숫자>` 리터럴 수. 알려진 블록 밖의 포트를 탐지한다. */
function vitePortLiteralCount(): number {
  const src = fs.readFileSync(VITE_CONFIG, 'utf8');
  return [...src.matchAll(/port:\s*\d+/g)].length;
}

/**
 * `application.yml` 의 `BTS_CORS_ALLOWED_ORIGINS` **기본값**을 수집한다.
 *
 * 기본값을 보는 이유. 로컬 손검증과 CI 는 환경변수를 주지 않으므로 이 기본값이 곧 실제 허용목록이다.
 */
function corsAllowedOriginDefaults(): Set<string> {
  const src = fs.readFileSync(APP_YML, 'utf8');
  const defaults = /allowed-origins:\s*\$\{BTS_CORS_ALLOWED_ORIGINS:([^}]*)\}/.exec(src)?.[1] ?? '';

  return new Set(
    defaults
      .split(',')
      .map((origin) => origin.trim())
      .filter((origin) => origin.length > 0),
  );
}

/**
 * `backend-ci.yml` 의 `on.<trigger>.paths` 를 **트리거별로** 수집한다.
 *
 * 한 덩어리로 합치면 `pull_request` 에만 배선돼도 통과한다 — 봉인이 절반만 닫히는 양식이다.
 * 정규식으로 블록을 잘라내는 대신 줄 단위로 읽는다. YAML 은 들여쓰기가 곧 구조라
 * 들여쓰기를 상태로 쓰는 편이 주석·빈 줄에 견고하다.
 */
function backendCiTriggerPaths(): Map<string, Set<string>> {
  const collected = new Map<string, Set<string>>();
  let trigger: string | undefined;
  let inPaths = false;

  for (const line of fs.readFileSync(BACKEND_CI, 'utf8').split('\n')) {
    const triggerKey = /^ {2}([a-z_]+):/.exec(line)?.[1];
    if (triggerKey !== undefined) {
      trigger = triggerKey;
      inPaths = false;
      continue;
    }
    if (/^ {4}paths:/.test(line)) {
      inPaths = true;
      continue;
    }
    // `branches:` 같은 paths 의 형제 키를 만나면 목록이 끝났다.
    if (/^ {4}\S/.test(line)) {
      inPaths = false;
      continue;
    }

    const item = /^ {6}- '(.+)'$/.exec(line)?.[1];
    if (!inPaths || trigger === undefined || item === undefined) continue;

    const paths = collected.get(trigger) ?? new Set<string>();
    paths.add(item);
    collected.set(trigger, paths);
  }

  return collected;
}

describe('vite 로컬 포트 ↔ 백엔드 CORS 허용 오리진 정합', () => {
  test('판별식이 비어 있지 않다 (양성 대조군)', () => {
    const blocks = viteServeBlocks();
    const origins = corsAllowedOriginDefaults();
    const triggers = backendCiTriggerPaths();

    // 하한이 없으면 파서가 0건을 내도 아래 차집합이 전부 공허하게 통과한다.
    // 0 이라는 결과는 「없다」가 아니라 「내 판별식이 틀렸다」를 먼저 의심해야 한다.
    assert.deepEqual(
      [...VITE_SERVE_BLOCKS].filter((name) => !blocks.has(name)),
      [],
      `vite.config.ts 에서 파싱하지 못한 서빙 블록이 있다 — 파서가 고장났거나 블록이 개명됐다.`,
    );
    assert.ok(
      origins.size >= MIN_CORS_ORIGINS,
      `application.yml 에서 CORS 허용 오리진을 ${origins.size}건 찾았다 — 파서가 고장났다.`,
    );
    for (const trigger of CI_TRIGGERS) {
      const paths = triggers.get(trigger);
      assert.ok(
        paths !== undefined && paths.size >= REQUIRED_CI_TRIGGER_PATHS.length,
        `backend-ci.yml 의 on.${trigger}.paths 를 ${paths?.size ?? 0}건 찾았다 — 파서가 고장났다.`,
      );
    }
  });

  test('vite 가 서빙하는 모든 로컬 포트가 CORS 허용목록에 있다', () => {
    const origins = corsAllowedOriginDefaults();

    const uncovered = [...viteServeBlocks()]
      .map(([name, block]) => ({ name, origin: `http://localhost:${block.port}` }))
      .filter(({ origin }) => !origins.has(origin));

    assert.deepEqual(
      uncovered.map(({ name, origin }) => `${name} → ${origin}`),
      [],
      `CORS 허용목록에 없는 vite 서빙 오리진이 있다.\n` +
        `허용됨=${[...origins].join(', ')}\n` +
        `브라우저는 POST 에만 Origin 을 보내므로 GET 은 통과하고 **POST 만** ` +
        `403 Invalid CORS request 가 된다 — 로그인부터 막혀 원인 추적이 어렵다.\n` +
        `vite 포트를 허용목록에 맞추거나, 허용목록을 넓혀야 한다(후자는 운영 기본값이 넓어진다).`,
    );
  });

  test('알려진 서빙 블록 밖에 포트 리터럴이 없다', () => {
    const known = viteServeBlocks().size;
    const total = vitePortLiteralCount();

    // 상한이 없으면 새 포트가 조용히 늘어도 위 차집합이 그것을 보지 못한다.
    // 「침묵하는 상한」을 두지 않는다 — 갭이 생기면 알려줘야 한다.
    assert.equal(
      total,
      known,
      `vite.config.ts 의 port 리터럴 ${total}건 중 ${known}건만 판별식이 보고 있다.\n` +
        `새 서빙 블록을 추가했다면 VITE_SERVE_BLOCKS 에 등록해야 이 판별식이 그 포트도 검사한다.`,
    );
  });

  test('서빙 블록이 strictPort 로 고정돼 있다', () => {
    const drifting = [...viteServeBlocks()]
      .filter(([, block]) => !block.strictPort)
      .map(([name]) => name);

    assert.deepEqual(
      drifting,
      [],
      `strictPort 가 없는 서빙 블록이 있다: ${drifting.join(', ')}\n` +
        `server 와 preview 가 같은 포트를 쓰므로, 점유 시 vite 는 **조용히 다음 포트로 옮겨** 붙는다. ` +
        `그러면 그 포트는 CORS 허용목록 밖이라 같은 403 이 다른 원인으로 재발한다.`,
    );
  });

  test('양쪽 트리거가 판별식 입력 경로를 전부 건다', () => {
    const triggers = backendCiTriggerPaths();

    const missing = CI_TRIGGERS.flatMap((trigger) => {
      const paths = triggers.get(trigger) ?? new Set<string>();
      return REQUIRED_CI_TRIGGER_PATHS.filter((required) => !paths.has(required)).map(
        (required) => `on.${trigger}.paths 에 '${required}' 없음`,
      );
    });

    assert.deepEqual(
      missing,
      [],
      `판별식 입력이 바뀌어도 CI 가 안 도는 경로가 있다.\n` +
        `${missing.join('\n')}\n` +
        `이 판별식은 backend-ci 의 workflow-scripts 잡(pnpm test:workflow)에서 돈다. ` +
        `입력 파일이 트리거에 없으면 그 파일만 바꾼 PR 에서 판별식이 0회 실행된다.`,
    );
  });

  test('E2E 가 이미 떠 있는 서버를 재사용하지 않는다', () => {
    const src = fs.readFileSync(PLAYWRIGHT_CONFIG, 'utf8');

    // preview(프로덕션 빌드)와 dev 가 같은 포트를 쓰게 됐으므로, 손검증용 preview 를 켜 둔 채
    // E2E 를 돌리면 Playwright 가 그것을 재사용한다. 프로덕션 빌드는 MSW 가 꺼져 있어
    // 전 테스트가 API 호출부터 실패한다 — 거짓 초록은 아니지만 원인이 전혀 안 보이는 실패다.
    assert.match(
      src,
      /reuseExistingServer:\s*false/,
      `playwright.config.ts 의 reuseExistingServer 가 false 로 고정돼 있지 않다.\n` +
        `preview 와 dev 가 같은 포트(5173)를 쓰므로 재사용을 허용하면 E2E 가 프로덕션 빌드를 ` +
        `잡고 돌 수 있다. false 면 Playwright 가 항상 자기 dev 서버를 띄우고, 포트가 점유돼 ` +
        `있으면 strictPort 때문에 즉시 명시적으로 실패한다.`,
    );
  });
});
