import { AppHeader } from '@/components/AppHeader';
import { useAuth } from '@/features/auth/context/AuthContext';

/**
 * Placeholder de /dashboard (Lote H T7.10). Hoy solo confirma que la sesión está activa y
 * sirve de destino del flujo MFA. Los módulos reales (trading, portafolio, mercado) viven en
 * sprints siguientes; cuando lleguen, esta página se reemplaza por su propio layout.
 */
export function DashboardPage() {
  const { user } = useAuth();

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-12">
        <h1 className="text-3xl font-bold">
          Bienvenido, {user?.nombreCompleto ?? 'inversionista'}.
        </h1>
        <p className="mt-3 text-slate-400">
          Tu sesión está activa. Próximamente vas a ver acá tu portafolio, tu dashboard de
          mercado y el módulo de trading para los 5 mercados internacionales.
        </p>
        <p className="mt-6 rounded-md border border-slate-800 bg-slate-900/60 px-4 py-3 text-sm text-slate-400">
          MVP en construcción — esta vista es un placeholder de las HUs F09/F10/F16/F18 del
          Sprint 2.
        </p>
      </main>
    </div>
  );
}
