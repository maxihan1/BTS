// automation-git-webhooks Zod 스키마 단위 테스트 — backend CreateGitWebhookResponse/GitWebhookSummaryResponse DTO 1:1 대응 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import { gitProviderSchema, createGitWebhookResponseSchema, gitWebhookSummarySchema } from './automation-git-webhooks.types'

// ─────────────────────────────────────────────────────────────────────────────
// gitProviderSchema
// FR3 판별자 — 정확히 2값. 소문자·BITBUCKET 거부(대문자 원문 전송 계약, GitProvider.kt:16-17)
// ─────────────────────────────────────────────────────────────────────────────

describe('gitProviderSchema', () => {
  it('GITHUB/GITLAB 만 받는다', () => {
    expect(gitProviderSchema.parse('GITHUB')).toBe('GITHUB')
    expect(gitProviderSchema.parse('GITLAB')).toBe('GITLAB')
    expect(() => gitProviderSchema.parse('github')).toThrow()
    expect(() => gitProviderSchema.parse('BITBUCKET')).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createGitWebhookResponseSchema
// NFR4 판별자 — origin 없는 절대경로가 통과해야 한다. .url() 을 붙이면 red.
// ─────────────────────────────────────────────────────────────────────────────

describe('createGitWebhookResponseSchema', () => {
  it('webhookUrl 은 origin 없는 절대경로를 통과시킨다', () => {
    const parsed = createGitWebhookResponseSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      provider: 'GITHUB',
      webhookUrl: '/api/v1/webhooks/git/abc123',
      token: 'abc123',
    })
    expect(parsed.webhookUrl).toBe('/api/v1/webhooks/git/abc123')
  })

  it('완전한 URL(origin 포함)도 여전히 문자열로 통과시킨다', () => {
    const parsed = createGitWebhookResponseSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      provider: 'GITLAB',
      webhookUrl: 'https://bts.example.com/api/v1/webhooks/git/abc123',
      token: 'abc123',
    })
    expect(parsed.webhookUrl).toBe('https://bts.example.com/api/v1/webhooks/git/abc123')
  })

  it('token 필드(1회 노출 원문)를 그대로 보존한다', () => {
    const parsed = createGitWebhookResponseSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      provider: 'GITHUB',
      webhookUrl: '/api/v1/webhooks/git/abc123',
      token: 'abc123',
    })
    expect(parsed.token).toBe('abc123')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// gitWebhookSummarySchema
// NFR5 — Instant = ISO 문자열(epoch 배열 아님)
// ─────────────────────────────────────────────────────────────────────────────

describe('gitWebhookSummarySchema', () => {
  it('id·provider·createdAt·createdBy 4필드를 파싱한다', () => {
    const parsed = gitWebhookSummarySchema.parse({
      id: '22222222-2222-4222-8222-222222222222',
      provider: 'GITLAB',
      createdAt: '2026-07-10T10:00:00Z',
      createdBy: '33333333-3333-4333-8333-333333333333',
    })
    expect(parsed).toEqual({
      id: '22222222-2222-4222-8222-222222222222',
      provider: 'GITLAB',
      createdAt: '2026-07-10T10:00:00Z',
      createdBy: '33333333-3333-4333-8333-333333333333',
    })
  })

  it('token·secret 관련 필드가 섞여 들어와도 노출 형태에는 존재하지 않는다', () => {
    const parsed = gitWebhookSummarySchema.parse({
      id: '22222222-2222-4222-8222-222222222222',
      provider: 'GITHUB',
      createdAt: '2026-07-10T10:00:00Z',
      createdBy: '33333333-3333-4333-8333-333333333333',
      token: 'leaked-if-schema-were-wrong',
    })
    expect(parsed).not.toHaveProperty('token')
  })

  it('createdAt 이 ISO datetime 문자열이 아니면 reject한다', () => {
    expect(() =>
      gitWebhookSummarySchema.parse({
        id: '22222222-2222-4222-8222-222222222222',
        provider: 'GITHUB',
        createdAt: 1752141600000,
        createdBy: '33333333-3333-4333-8333-333333333333',
      }),
    ).toThrow(ZodError)
  })
})
