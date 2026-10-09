import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { get, isReadPath, params } from '../api/client'
import { allowedProxyRequest } from '../../vite.config'
afterEach(() => vi.unstubAllGlobals())
describe('read-only transport and server proxy guard', () => {
  it('allows only known GET paths; no create, destroy, root or authorization', () => {
    for (const path of ['/api/v1/experiments', '/api/v1/experiments/1/destroy', '/api/v1/console/create', '/actuator/env', '/api/v1/root', '/api/v1/authorization']) {
      expect(isReadPath(path)).toBe(false)
      expect(allowedProxyRequest('GET', path)).toBe(false)
    }
    expect(allowedProxyRequest('POST', '/api/v1/console/overview')).toBe(false)
    expect(allowedProxyRequest('DELETE', '/api/v1/console/overview')).toBe(false)
    expect(isReadPath('/api/v1/console/overview')).toBe(true)
  })
  it('encodes filters, omits empty values', () => expect(params({ search: "a&status=SUCCESS", page: 0, status: '' })).toBe('?search=a%26status%3DSUCCESS&page=0'))
  it('uses only GET and passes AbortSignal', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{"status":"UP"}'))
    vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    expect(await get('/actuator/health', z.object({ status: z.string() }), signal)).toEqual({ status: 'UP' })
    expect(fetch).toHaveBeenCalledWith('/actuator/health', { method: 'GET', signal, headers: { Accept: 'application/json' } })
  })
  it('rejects writes without network dispatch', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(get('/api/v1/experiments', z.unknown())).rejects.toThrow('不允许')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('never leaks raw server/root failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('/root/private LD_PRELOAD=secret', { status: 500 })))
    await expect(get('/actuator/health', z.unknown())).rejects.toThrow('HTTP 500')
  })
  it('fails closed on schema mismatch, not a fake fallback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":true}')))
    await expect(get('/actuator/health', z.object({ status: z.string() }))).rejects.toThrow('契约不匹配')
  })
})
