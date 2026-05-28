import { type ReactNode } from 'react';

/**
 * Atmósfera de fondo del revamp UI: tres orbes blurred animados sobre gradiente
 * base slate-950. Provee la "luz" que el glassmorphism difumina detrás del vidrio
 * — sin esto los cards parecen rectángulos translúcidos planos.
 *
 * <p>Performance: los tres orbes son `fixed` + `blur-3xl` (GPU composited) y las
 * animaciones son keyframes CSS de 24–32s (no JS loop). Cero re-render React.
 */
export function GlassBackground({ children }: { children: ReactNode }) {
  return (
    <div className="relative min-h-screen overflow-x-hidden bg-slate-950 text-slate-100">
      <div
        aria-hidden
        className="pointer-events-none fixed inset-0 z-0 bg-gradient-to-br from-slate-950 via-slate-950 to-slate-900"
      />

      <div
        aria-hidden
        className="pointer-events-none fixed -left-32 -top-32 z-0 h-[480px] w-[480px] animate-orb-drift-1 rounded-full bg-violet-600/30 blur-3xl"
      />

      <div
        aria-hidden
        className="pointer-events-none fixed -bottom-40 -right-32 z-0 h-[520px] w-[520px] animate-orb-drift-2 rounded-full bg-cyan-500/20 blur-3xl"
      />

      <div
        aria-hidden
        className="pointer-events-none fixed left-1/2 top-1/2 z-0 h-[600px] w-[600px] -translate-x-1/2 -translate-y-1/2 animate-orb-drift-3 rounded-full bg-fuchsia-600/10 blur-3xl"
      />

      <div className="relative z-10">{children}</div>
    </div>
  );
}
