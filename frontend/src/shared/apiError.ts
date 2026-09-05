import { isAxiosError, type AxiosResponse } from 'axios'
import { err, ok, type Result } from './result'

export type FieldError = { field: string; message: string }

export type ProblemDetail = {
  status?: number
  title?: string
  detail?: string
  errors?: FieldError[]
}

export type ApiError<F extends string = string> = {
  status: number | null
  message: string
  fields: Partial<Record<F, string>>
}

export const NETWORK_ERROR_MESSAGE = 'Could not reach the server'

export function toApiError<F extends string = string>(error: unknown): ApiError<F> {
  if (isAxiosError(error) && error.response) {
    const { status, data } = error.response
    const problem: ProblemDetail = typeof data === 'object' && data !== null ? data : {}
    return {
      status,
      message: problem.detail ?? problem.title ?? `Request failed with status ${status}`,
      fields: fieldErrors(problem.errors),
    }
  }
  return { status: null, message: NETWORK_ERROR_MESSAGE, fields: {} }
}

function fieldErrors<F extends string>(
  errors: FieldError[] | undefined,
): Partial<Record<F, string>> {
  const fields: Partial<Record<F, string>> = {}
  for (const { field, message } of errors ?? []) {
    fields[field as F] ??= message
  }
  return fields
}

export async function safeRequest<T, F extends string = string>(
  request: Promise<AxiosResponse<T>>,
): Promise<Result<T, ApiError<F>>> {
  try {
    return ok((await request).data)
  } catch (e) {
    return err(toApiError<F>(e))
  }
}
