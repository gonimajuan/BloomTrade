import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/features/auth/context/AuthContext';

/**
 * Header de la app autenticada (spec HU-F02 §12.2 — Lote H T7.9).
 *
 * <p>Decisión D18: el "logout" no llama al backend — limpia el AuthContext y navega a /login.
 * Cuando llegue la mini-HU de token-rotation-logout, este componente disparará la mutación de
 * logout antes de limpiar el state.
 */
export function AppHeader() {
  const { user, clearSession } = useAuth();
  const navigate = useNavigate();

  const onLogout = () => {
    clearSession();
    navigate('/login', { replace: true });
  };

  return (
    <header className="flex items-center justify-between border-b border-slate-800 bg-slate-950/80 px-6 py-3 text-slate-100">
      <span className="text-lg font-semibold text-white">BloomTrade</span>
      <div className="flex items-center gap-4 text-sm">
        {user && (
          <span className="text-slate-300">
            {user.nombreCompleto}
            <span className="ml-2 rounded-md bg-slate-800 px-2 py-0.5 text-xs uppercase tracking-wide text-slate-400">
              {user.rol}
            </span>
          </span>
        )}
        <Link
          to="/dashboard"
          className="text-slate-300 underline-offset-4 hover:text-white hover:underline"
        >
          Dashboard
        </Link>
        <Link
          to="/profile"
          className="text-slate-300 underline-offset-4 hover:text-white hover:underline"
        >
          Mi perfil
        </Link>
        <Link
          to="/premium"
          className="text-slate-300 underline-offset-4 hover:text-white hover:underline"
        >
          Premium
        </Link>
        <Link
          to="/trade"
          className="text-slate-300 underline-offset-4 hover:text-white hover:underline"
        >
          Operar
        </Link>
        <Link
          to="/portfolio"
          className="text-slate-300 underline-offset-4 hover:text-white hover:underline"
        >
          Portafolio
        </Link>
        <button
          type="button"
          onClick={onLogout}
          className="rounded-md border border-slate-700 px-3 py-1 text-slate-200 hover:border-slate-500 hover:bg-slate-900"
        >
          Cerrar sesión
        </button>
      </div>
    </header>
  );
}
