// QA 테스트 계정을 만들고 3개 QA 프로젝트에 참여시킨다 — 해시는 앱이 만들고, DB 는 username 개명에만 쓴다

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Api, parseArgs, requireEnv, loadLocalEnv } from './lib.mjs';
import { PROJECTS } from './content.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
loadLocalEnv(HERE);
const { opts } = parseArgs(process.argv);

const BASE_URL = opts['base-url'] ?? process.env.BTS_BASE_URL ?? 'https://bts.maxihan.com';

/** API 로 만들 때 쓰는 임시 username. `@` 가 금지라 여기선 못 쓰고, 마지막에 DB 에서 개명한다. */
const API_USERNAME = opts['api-username'] ?? 'test';
/** 최종 로그인 ID. `@` 때문에 API 검증(`^[A-Za-z0-9._-]+$`)을 통과할 수 없다. */
const FINAL_USERNAME = opts['final-username'] ?? 'test@test.com';
const EMAIL = opts.email ?? 'test@test.com';
const DISPLAY_NAME = opts['display-name'] ?? 'QA 테스트 계정';
const PASSWORD = opts.password ?? 'Qwer!234Qwer!';
const ROLE = opts.role ?? 'PROJECT_ADMIN';

const log = (...a) => console.log(...a);

/**
 * ★해시를 손으로 만들지 않는 이유.
 *
 * `LocalCredentialService` 는 argon2-jvm 으로 `m=65536,t=3,p=4` argon2id 를 쓴다. 같은 문자열을
 * 다른 구현으로 만들어 넣으면 파라미터·salt 길이·인코딩이 하나만 어긋나도 증상이 "로그인 실패" 하나로만
 * 나타나 원인이 보이지 않는다. 그래서 **자격증명은 전부 앱의 정상 경로**로 만들고,
 * DB 직접 조작은 API 가 구조적으로 막는 한 곳(username 의 `@`)에만 쓴다.
 */
async function main() {
  const adminApi = new Api({
    baseUrl: BASE_URL,
    provider: process.env.BTS_PROVIDER ?? 'local',
    username: opts['admin-username'] ?? requireEnv('BTS_ADMIN_USERNAME'),
    password: opts['admin-password'] ?? requireEnv('BTS_ADMIN_PASSWORD'),
    totp: opts.totp ?? null,
  });

  log(`대상 서버: ${BASE_URL}`);
  await adminApi.login();

  // 1. 계정 생성 — 임시 비밀번호(must_change=true)를 응답에서 한 번만 준다.
  let userId;
  let temporaryPassword;
  const existing = (await adminApi.get(`/api/v1/users?query=${encodeURIComponent(API_USERNAME)}`))
    .find((u) => u.username === API_USERNAME || u.username === FINAL_USERNAME);
  if (existing) {
    log(`계정 '${existing.username}' 가 이미 있다 (${existing.id}) — 생성을 건너뛴다`);
    userId = existing.id;
  } else {
    const created = await adminApi.post('/api/v1/users', {
      username: API_USERNAME, email: EMAIL, displayName: DISPLAY_NAME,
    });
    userId = created.id;
    temporaryPassword = created.temporaryPassword;
    log(`계정 생성: ${created.username} (${userId})`);
  }

  // 2. 비밀번호를 원하는 값으로 교체 — 본인 세션으로만 가능하다(POST /users/me/password).
  //    이 호출이 must_change 플래그도 함께 내린다.
  if (temporaryPassword) {
    const selfApi = new Api({
      baseUrl: BASE_URL, provider: process.env.BTS_PROVIDER ?? 'local',
      username: API_USERNAME, password: temporaryPassword,
    });
    await selfApi.login();
    await selfApi.post('/api/v1/users/me/password', {
      currentPassword: temporaryPassword, newPassword: PASSWORD,
    });
    log('비밀번호 교체 완료 (must_change 해제)');
  } else {
    log('임시 비밀번호를 받지 못했다 — 비밀번호는 그대로 둔다');
  }

  // 3. 3개 프로젝트 멤버십. 프로젝트 생성자(QA 계정)가 PROJECT_ADMIN 이므로 그 세션으로 넣는다.
  const ownerApi = new Api({
    baseUrl: BASE_URL, provider: process.env.BTS_PROVIDER ?? 'local',
    username: requireEnv('BTS_QA_USERNAME'), password: requireEnv('BTS_QA_PASSWORD'),
  });
  await ownerApi.login();
  for (const p of PROJECTS) {
    try {
      await ownerApi.post(`/api/v1/projects/${p.key}/members`, { userId, role: ROLE }, { expect: [200, 201, 409] });
      log(`  · ${p.key} 멤버 추가 (${ROLE})`);
    } catch (e) {
      log(`  ! ${p.key} 멤버 추가 실패: ${e.message.slice(0, 200)}`);
    }
  }

  log('\n─────────────────────────────');
  log(`로그인 ID   ${API_USERNAME}`);
  log(`비밀번호    ${PASSWORD}`);
  log(`이메일      ${EMAIL}`);
  log(`userId      ${userId}`);
  log('─────────────────────────────');
  log(`\n로그인 ID 는 '${API_USERNAME}' 다 — '${FINAL_USERNAME}' 이 아니다.`);
  log("API 의 username 규칙 '^[A-Za-z0-9._-]+$' 가 '@' 를 허용하지 않는다. 이메일 필드에만 들어간다.");
}

main().catch((e) => { console.error(`\n실패: ${e.message}`); process.exit(1); });
