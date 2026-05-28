import { Link, NavLink, useNavigate } from 'react-router-dom';
import { LogOut, User as UserIcon } from 'lucide-react';
import { useAuth } from '@/features/auth/context/AuthContext';
import { UserDropdown, UserDropdownItem } from '@/components/ui/UserDropdown';
import { Badge } from '@/components/ui/Badge';
import { cn } from '@/lib/cn';

const navLinks = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/portfolio', label: 'Portafolio' },
  { to: '/trade', label: 'Operar' },
  { to: '/premium', label: 'Premium' },
];

function getInitials(name: string | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return parts
    .slice(0, 2)
    .map((p) => p[0] ?? '')
    .join('')
    .toUpperCase();
}

/**
 * Header glass sticky del revamp UI (Lote C). NavLink con pill active state +
 * UserDropdown manual con avatar de iniciales. El logout sigue limpiando el
 * AuthContext localmente (sin call al backend) hasta que la mini-HU de
 * token-rotation-logout introduzca el endpoint.
 */
export function AppHeader() {
  const { user, clearSession } = useAuth();
  const navigate = useNavigate();

  const onLogout = () => {
    clearSession();
    navigate('/login', { replace: true });
  };

  const initials = getInitials(user?.nombreCompleto);

  return (
    <header className="sticky top-0 z-40 border-b border-white/10 bg-slate-950/60 backdrop-blur-xl">
      <div className="mx-auto flex max-w-7xl items-center justify-between gap-6 px-6 py-3">
        <Link
          to="/dashboard"
          className="flex items-center gap-2 text-white transition-opacity hover:opacity-80"
        >
          <span aria-hidden className="text-lg text-violet-400">
            ❖
          </span>
          <span className="text-base font-semibold tracking-tight">BloomTrade</span>
        </Link>

        <nav className="flex items-center gap-1">
          {navLinks.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              className={({ isActive }) =>
                cn(
                  'rounded-xl px-3 py-1.5 text-sm font-medium transition-all duration-200',
                  isActive
                    ? 'bg-violet-500/15 text-violet-200 shadow-glow-violet-sm'
                    : 'text-slate-300 hover:bg-white/5 hover:text-white',
                )
              }
            >
              {link.label}
            </NavLink>
          ))}
        </nav>

        {user && (
          <UserDropdown
            trigger={
              <span className="flex items-center gap-2">
                <span
                  aria-hidden
                  className="flex h-7 w-7 items-center justify-center rounded-full bg-violet-500/30 text-xs font-semibold text-violet-100"
                >
                  {initials}
                </span>
                <span className="text-sm text-slate-200">{user.nombreCompleto}</span>
              </span>
            }
          >
            <div className="border-b border-white/10 px-3 pb-3 pt-2">
              <p className="text-sm font-medium text-white">{user.nombreCompleto}</p>
              <Badge variant="accent" className="mt-1.5 uppercase tracking-wide">
                {user.rol}
              </Badge>
            </div>
            <UserDropdownItem onClick={() => navigate('/profile')}>
              <UserIcon className="h-4 w-4" aria-hidden />
              Mi perfil
            </UserDropdownItem>
            <UserDropdownItem variant="destructive" onClick={onLogout}>
              <LogOut className="h-4 w-4" aria-hidden />
              Cerrar sesión
            </UserDropdownItem>
          </UserDropdown>
        )}
      </div>
    </header>
  );
}
