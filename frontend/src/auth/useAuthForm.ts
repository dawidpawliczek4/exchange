import { ref } from 'vue'
import router from '@/router'
import type { AuthResult } from '@/auth/store'
import { validateFields, type AuthFields, type AuthSchema } from '@/auth/validation'

type FieldErrors = Partial<Record<AuthFields, string>>

export function useAuthForm(
  schema: AuthSchema,
  submit: (email: string, password: string) => Promise<AuthResult>,
) {
  const email = ref('')
  const password = ref('')
  const fields = ref<FieldErrors>({})
  const message = ref<string | null>(null)
  const pending = ref(false)

  async function onSubmit() {
    message.value = null
    fields.value = validateFields(schema, { email: email.value, password: password.value })
    if (Object.keys(fields.value).length > 0) return

    pending.value = true
    try {
      const result = await submit(email.value, password.value)
      if (result.ok) {
        await router.push('/')
        return
      }
      fields.value = result.error.fields
      if (Object.keys(result.error.fields).length === 0) message.value = result.error.message
    } finally {
      pending.value = false
    }
  }

  return { email, password, fields, message, pending, onSubmit }
}
