import { z } from 'zod'

export const LoginSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').pipe(z.email('Email is invalid')),
  password: z.string().min(1, 'Password is required'),
})

export const RegisterSchema = LoginSchema.extend({
  password: z
    .string()
    .min(1, 'Password is required')
    .pipe(
      z
        .string()
        .min(8, 'Password must be between 8 and 72 characters')
        .max(72, 'Password must be between 8 and 72 characters'),
    ),
})

export type AuthValues = z.infer<typeof LoginSchema>
export type AuthFields = keyof AuthValues
export type AuthSchema = z.ZodType<AuthValues, AuthValues>

export function validateFields(
  schema: AuthSchema,
  values: AuthValues,
): Partial<Record<AuthFields, string>> {
  const result = schema.safeParse(values)
  if (result.success) return {}

  const fields: Partial<Record<AuthFields, string>> = {}
  for (const issue of result.error.issues) {
    const field = issue.path[0] as AuthFields | undefined
    if (field !== undefined) fields[field] ??= issue.message
  }
  return fields
}
