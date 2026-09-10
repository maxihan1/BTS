// lockfile 의 @playwright/test 버전 ⟺ Jenkinsfile.e2e 의 공식 이미지 태그 차집합 판별식
//
// ## 무엇을 막는가
//
// E2E 는 Playwright 공식 컨테이너에서 돈다(젠킨스 이미지에 크롬 의존 라이브러리를 넣지
// 않으려고 — uid 1000 이라 `--with-deps` 가 apt 권한 없이 **종료 코드 0 으로 조용히** 넘어가고
// 크롬은 `libglib-2.0.so.0` 부재로 안 뜬다. 2026-09-10 실측).
//
// 그 이미지의 태그와 저장소가 설치하는 `@playwright/test` 버전은 **두 개의 목록**이다.
// 갈리면 이미지에 든 브라우저 리비전과 라이브러리가 기대하는 값이 어긋나
//   Executable doesn't exist at /ms-playwright/chromium-XXXX/chrome-linux/chrome
// 로 죽거나, 더 나쁘게는 **일부만 어긋나 특정 테스트만** 실패한다.
//
// 한쪽만 올리는 것은 아주 쉽다 — `pnpm up` 은 lockfile 만 바꾸고 Jenkinsfile 은 안 본다.
//
// ## 왜 lockfile 을 정본으로 삼나
//
// `package.json` 은 `^1.60.0` 같은 범위라 **실제 설치본이 아니다.** 컨테이너 안에서 도는
// 것은 lockfile 이 고정한 값이므로 대조 대상도 그것이어야 한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const LOCKFILE = join(ROOT, 'pnpm-lock.yaml');
const JENKINSFILE = join(ROOT, 'Jenkinsfile.e2e');
// ★`bts-ci` 도 같은 이미지를 쓴다(빠른 게이트의 변경 도메인 E2E). 태그가 세 곳
//   (lockfile · Jenkinsfile.e2e · Jenkinsfile)에 있으므로 전부 대조한다 — 두 곳만 보면
//   나머지 하나가 조용히 낡는다.
const CI_JENKINSFILE = join(ROOT, 'Jenkinsfile');

/** lockfile 이 고정한 `@playwright/test` 실제 버전. */
function lockedVersion(): string {
  const lock = readFileSync(LOCKFILE, 'utf-8');
  const m = lock.match(/^ {2}'@playwright\/test@([\d.]+)':/m);
  assert.ok(m, "pnpm-lock.yaml 에서 '@playwright/test@<버전>' 항목을 못 찾았다 — 파서가 낡았다.");
  return m[1];
}

/** `Jenkinsfile.e2e` 의 `PW_IMAGE` 태그에서 뽑은 버전. */
function imageVersion(): string {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  const m = jf.match(/PW_IMAGE\s*=\s*'mcr\.microsoft\.com\/playwright:v([\d.]+)-[a-z]+'/);
  assert.ok(
    m,
    "Jenkinsfile.e2e 에서 PW_IMAGE = 'mcr.microsoft.com/playwright:v<버전>-<배포판>' 을 못 찾았다.\n" +
      '형식을 바꿨다면 이 판별식도 함께 고쳐야 한다 — 안 고치면 대조가 사라진다.',
  );
  return m[1];
}

test('★Playwright 이미지 태그가 lockfile 버전과 정확히 같다', () => {
  const locked = lockedVersion();
  const image = imageVersion();

  // 양성 대조군. 파서가 빈 문자열을 뽑으면 아래 비교가 「'' === ''」로 언제나 통과한다.
  assert.match(locked, /^\d+\.\d+\.\d+$/, `lockfile 버전을 못 읽었다: ${locked}`);
  assert.match(image, /^\d+\.\d+\.\d+$/, `이미지 태그 버전을 못 읽었다: ${image}`);

  assert.equal(
    image,
    locked,
    `Playwright 판이 갈렸다.\n` +
      `  lockfile        ${locked}  (컨테이너 안에서 실제로 도는 것)\n` +
      `  Jenkinsfile.e2e ${image}  (이미지에 든 브라우저)\n\n` +
      `둘이 다르면 브라우저 리비전 값이 어긋나 "Executable doesn't exist" 로 죽거나,\n` +
      `더 나쁘게는 일부 테스트만 조용히 실패한다.\n` +
      `\`pnpm up\` 으로 lockfile 만 올리면 이렇게 된다 — Jenkinsfile.e2e 의 PW_IMAGE 도 함께 고쳐라.`,
  );
});

test('★bts-ci 의 PW_IMAGE 도 같은 태그다 (세 목록 전부 대조)', () => {
  const ci = readFileSync(CI_JENKINSFILE, 'utf-8');
  const m = ci.match(/PW_IMAGE\s*=\s*'mcr\.microsoft\.com\/playwright:v([\d.]+)-[a-z]+'/);
  assert.ok(m, "Jenkinsfile 에서 PW_IMAGE 를 못 찾았다 — 빠른 게이트가 로컬 바이너리로 떨어진다.");
  assert.equal(
    m[1],
    lockedVersion(),
    `bts-ci 의 Playwright 이미지가 lockfile 과 갈렸다.\n` +
      `  lockfile   ${lockedVersion()}\n  Jenkinsfile ${m[1]}`,
  );
});

test('E2E 파이프라인이 공식 이미지를 쓴다 (젠킨스 이미지에 크롬을 넣지 않는다)', () => {
  const jf = readFileSync(JENKINSFILE, 'utf-8');
  // 셸 안에서는 `"$PW_IMAGE"` 로 참조된다. `docker run` 과 같은 sh 블록에 있으면 된다.
  assert.match(
    jf,
    /docker run[\s\S]{0,400}?"\$PW_IMAGE"/,
    'Jenkinsfile.e2e 가 PW_IMAGE 컨테이너로 Playwright 를 돌리지 않는다.\n' +
      '젠킨스 이미지에서 직접 돌리면 크롬이 뜨지 않는다 — libglib-2.0.so.0 부재(2026-09-10 실측).',
  );
});
