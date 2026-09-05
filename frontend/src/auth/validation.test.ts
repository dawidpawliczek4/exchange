import { describe, expect, it } from 'vitest'

import { LoginSchema, RegisterSchema, validateFields } from './validation'

describe('validateFields', () => {
  it('accepts a well-formed login', () => {
    expect(validateFields(LoginSchema, { email: 'a@b.com', password: 'x' })).toEqual({})
  })

  it('reports a missing email before a malformed one', () => {
    expect(validateFields(LoginSchema, { email: '  ', password: 'x' })).toEqual({
      email: 'Email is required',
    })
  })

  it('reports a malformed email', () => {
    expect(validateFields(LoginSchema, { email: 'not-an-email', password: 'x' })).toEqual({
      email: 'Email is invalid',
    })
  })

  it('reports every field at once', () => {
    expect(validateFields(LoginSchema, { email: '', password: '' })).toEqual({
      email: 'Email is required',
      password: 'Password is required',
    })
  })

  it('does not enforce the password length on login', () => {
    expect(validateFields(LoginSchema, { email: 'a@b.com', password: 'short' })).toEqual({})
  })

  it('enforces the password length on registration like the gateway does', () => {
    const message = 'Password must be between 8 and 72 characters'

    expect(validateFields(RegisterSchema, { email: 'a@b.com', password: 'short' })).toEqual({
      password: message,
    })
    expect(validateFields(RegisterSchema, { email: 'a@b.com', password: 'x'.repeat(73) })).toEqual({
      password: message,
    })
    expect(validateFields(RegisterSchema, { email: 'a@b.com', password: 'x'.repeat(8) })).toEqual(
      {},
    )
  })
})
