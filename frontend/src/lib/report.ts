import { ApiError } from '../api/client'
import { toast } from './toast'

/** Turn any failed API call into a toast that says what went wrong. */
export function report(e: unknown) {
  if (e instanceof ApiError) {
    if (e.status === 429) toast.push('warn', 'Rate limited', `${e.message}. Retry in ${e.retryAfter ?? '?'}s.`)
    else if (e.status === 401) toast.push('error', 'Not allowed', e.message)
    else toast.push('error', e.code ?? `HTTP ${e.status}`, e.message)
  } else {
    toast.push('error', 'Something went wrong', String(e))
  }
}
