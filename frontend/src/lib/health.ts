/** Shape of Spring Boot Actuator's /actuator/health with show-details=always. */
export type HealthStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'

export interface Health {
  status: HealthStatus
  components?: Record<string, { status: HealthStatus; details?: Record<string, unknown> }>
}

export type ServiceName = 'core' | 'gateway'

export async function fetchHealth(service: ServiceName, signal?: AbortSignal): Promise<Health> {
  const res = await fetch(`/${service}/actuator/health`, { signal })
  // Actuator answers 503 with a JSON body when a component is DOWN — still parse it.
  if (!res.ok && res.status !== 503) throw new Error(`HTTP ${res.status}`)
  return (await res.json()) as Health
}
