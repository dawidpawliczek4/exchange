import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'

const { push, refreshAccessToken, clearTokens } = vi.hoisted(() => ({
  push: vi.fn<(to: string) => Promise<void>>(),
  refreshAccessToken: vi.fn<() => Promise<string>>(),
  clearTokens: vi.fn<() => void>(),
}))

vi.mock('@/router', () => ({ default: { push } }))
vi.mock('@/auth/store', () => ({ useAuthStore: () => ({ refreshAccessToken, clearTokens }) }))

import { http } from './http'
import { ACCESS_KEY } from './localStorage'

type Reply = { status: number; data?: unknown }

const requests: InternalAxiosRequestConfig[] = []
let replies: Reply[] = []

http.defaults.adapter = async (config) => {
  requests.push(config)
  const reply = replies.shift() ?? { status: 200, data: 'ok' }
  const response = {
    status: reply.status,
    data: reply.data,
    statusText: '',
    headers: {},
    config,
  } as AxiosResponse
  if (reply.status >= 400) {
    throw new AxiosError('request failed', 'ERR_BAD_REQUEST', config, undefined, response)
  }
  return response
}

describe('http', () => {
  beforeEach(() => {
    localStorage.clear()
    requests.length = 0
    replies = []
    push.mockReset()
    push.mockResolvedValue(undefined)
    refreshAccessToken.mockReset()
    clearTokens.mockReset()
  })

  it('adds the bearer header only when an access token is stored', async () => {
    await http.get('/marketdata/candles')
    localStorage.setItem(ACCESS_KEY, 'access-1')
    await http.get('/marketdata/candles')

    expect(requests[0]?.headers.Authorization).toBeUndefined()
    expect(requests[1]?.headers.Authorization).toBe('Bearer access-1')
  })

  it('refreshes once on 401 and retries with the new token', async () => {
    localStorage.setItem(ACCESS_KEY, 'stale')
    replies = [{ status: 401 }, { status: 202, data: { id: 7 } }]
    refreshAccessToken.mockResolvedValue('access-2')

    const res = await http.post('/order', { qty: 1 })

    expect(res.status).toBe(202)
    expect(res.data).toEqual({ id: 7 })
    expect(refreshAccessToken).toHaveBeenCalledTimes(1)
    expect(requests).toHaveLength(2)
    expect(requests[1]?.headers.Authorization).toBe('Bearer access-2')
    expect(clearTokens).not.toHaveBeenCalled()
  })

  it('does not refresh on a 401 from the auth endpoints', async () => {
    replies = [{ status: 401 }]

    await expect(http.post('/auth/credentials/login', {})).rejects.toBeInstanceOf(AxiosError)

    expect(refreshAccessToken).not.toHaveBeenCalled()
    expect(requests).toHaveLength(1)
  })

  it('clears the tokens through the store and routes to login when the refresh fails', async () => {
    localStorage.setItem(ACCESS_KEY, 'stale')
    replies = [{ status: 401 }]
    refreshAccessToken.mockRejectedValue(new Error('refresh expired'))

    await expect(http.post('/order', {})).rejects.toThrow('refresh expired')

    expect(clearTokens).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith('/login')
    expect(requests).toHaveLength(1)
  })

  it('retries at most once', async () => {
    replies = [{ status: 401 }, { status: 401 }]
    refreshAccessToken.mockResolvedValue('access-2')

    await expect(http.post('/order', {})).rejects.toBeInstanceOf(AxiosError)

    expect(refreshAccessToken).toHaveBeenCalledTimes(1)
    expect(requests).toHaveLength(2)
    expect(clearTokens).not.toHaveBeenCalled()
  })
})
