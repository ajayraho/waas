/**
 * The WaaS mark: a countdown ring with three people waiting inside it.
 * Drawn heavier than the big logo (brand/logo.svg) so it stays crisp at 18px.
 * The ring follows the text colour (works in both themes); the dots are the "your turn" ochre.
 * Hovering the brand ticks the ring a quarter turn, like the timer moving on.
 */
export function LogoMark({ size = 18 }: { size?: number }) {
  return (
    <svg className="logo-mark" width={size} height={size} viewBox="0 0 512 512" aria-hidden>
      <path className="logo-ring" d="M 256 106 A 150 150 0 1 0 406 256" fill="none" stroke="currentColor" strokeWidth="40" />
      <circle className="logo-dot" cx="256" cy="180" r="36" fill="var(--turn)" />
      <circle className="logo-dot" cx="256" cy="256" r="36" fill="var(--turn)" />
      <circle className="logo-dot" cx="256" cy="332" r="36" fill="var(--turn)" />
    </svg>
  )
}
