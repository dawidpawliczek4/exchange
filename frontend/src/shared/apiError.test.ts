import { describe, expect, it } from 'vitest'
import { AxiosError, type AxiosResponse } from 'axios'

import { NETWORK_ERROR_MESSAGE, safeRequest, toApiError } from './apiError'

function axiosError(status: number, data: unknown): AxiosError {
  const response = { status, data, statusText: '', headers: {}, config: {} } as AxiosResponse
  return new AxiosError('request failed', 'ERR_BAD_REQUEST', undefined, undefined, response)
}

describe('toApiError', () => {
  it('uses the problem detail as the message', () => {
    const error = toApiError(
      axiosError(401, { status: 401, title: 'Unauthorized', detail: 'Invalid credentials' }),
    )

    expect(error).toEqual({ status: 401, message: 'Invalid credentials', fields: {} })
  })

  it('falls back to the title when there is no detail', () => {
    expect(toApiError(axiosError(404, { status: 404, title: 'Not Found' })).message).toBe(
      'Not Found',
    )
  })

  it('falls back to the status when the body is not a problem detail', () => {
    expect(toApiError(axiosError(502, '<html>bad gateway</html>')).message).toBe(
      'Request failed with status 502',
    )
  })

  it('maps validation errors onto fields, keeping the first message per field', () => {
    const error = toApiError<'email' | 'password'>(
      axiosError(400, {
        status: 400,
        detail: 'Invalid request content.',
        errors: [
          { field: 'email', message: 'Email is required' },
          { field: 'email', message: 'Email is invalid' },
          { field: 'password', message: 'Password is required' },
        ],
      }),
    )

    expect(error.fields).toEqual({ email: 'Email is required', password: 'Password is required' })
  })

  it('reports a network error when there is no response', () => {
    expect(toApiError(new AxiosError('timeout', 'ECONNABORTED'))).toEqual({
      status: null,
      message: NETWORK_ERROR_MESSAGE,
      fields: {},
    })
  })

  it('treats anything that is not an axios error as a network error', () => {
    expect(toApiError(new Error('boom')).message).toBe(NETWORK_ERROR_MESSAGE)
  })
})

describe('safeRequest', () => {
  it('unwraps the response body on success', async () => {
    const result = await safeRequest(
      Promise.resolve({ data: { accessToken: 'a' } } as AxiosResponse),
    )

    expect(result).toEqual({ ok: true, data: { accessToken: 'a' } })
  })

  it('converts a rejection into an api error', async () => {
    const result = await safeRequest(
      Promise.reject(axiosError(409, { detail: 'Email already taken' })),
    )

    expect(result).toEqual({
      ok: false,
      error: { status: 409, message: 'Email already taken', fields: {} },
    })
  })
})
