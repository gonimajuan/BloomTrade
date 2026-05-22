import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { configureAuthInterceptor } from '@/lib/apiClient';
import type { UserSummary } from '@/types/api';

/**
 * Estado de sesión persistido en localStorage (D23 — pragmatic fix sobre la decisión original
 * D12 HU-F02 §12.3 de "solo memoria").
 *
 * <p><strong>Trade-off de D23:</strong> en HU-F02 se decidió mantener el access token solo en
 * memoria porque un XSS no podría exfiltrar el token. Eso forzaba re-login en cada F5 / vuelta
 * de checkout Stripe / pestaña nueva — UX inaceptable para el demo del MVP. La solución
 * arquitectónicamente correcta (cookie HttpOnly + refresh tokens) sigue diferida en
 * `HU-F0X-token-rotation-logout` (mini-HU post-MVP). Mientras tanto persistimos en localStorage
 * con dos mitigaciones: (a) el access token expira en 15 min sin renovación silenciosa, así
 * que la ventana de exposición de un XSS es acotada; (b) un 401 en cualquier request limpia
 * automáticamente el storage vía {@code onUnauthorized}. La mini-HU eliminará este localStorage.
 */
const STORAGE_KEY_TOKEN = 'bloomtrade.accessToken';
const STORAGE_KEY_USER = 'bloomtrade.user';

function loadInitialToken(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY_TOKEN);
  } catch {
    return null; // private mode / storage disabled
  }
}

function loadInitialUser(): UserSummary | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_USER);
    return raw ? (JSON.parse(raw) as UserSummary) : null;
  } catch {
    return null;
  }
}

function persistSession(token: string | null, user: UserSummary | null) {
  try {
    if (token === null) localStorage.removeItem(STORAGE_KEY_TOKEN);
    else localStorage.setItem(STORAGE_KEY_TOKEN, token);
    if (user === null) localStorage.removeItem(STORAGE_KEY_USER);
    else localStorage.setItem(STORAGE_KEY_USER, JSON.stringify(user));
  } catch {
    // Storage no disponible — la sesión vive solo en memoria de esta pestaña.
  }
}

export interface AuthContextValue {
  user: UserSummary | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  setSession: (token: string, user: UserSummary) => void;
  clearSession: () => void;
  /**
   * Actualización optimista del usuario en memoria (HU-F04+F20 D12). Tras un PATCH /me exitoso
   * que cambie {@code nombreCompleto}, el header del app debe reflejarlo sin esperar al re-fetch.
   * No llama al backend: el caller es responsable de mantener consistencia con la BD.
   */
  updateUser: (partial: Partial<UserSummary>) => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(loadInitialUser);
  const [accessToken, setAccessToken] = useState<string | null>(loadInitialToken);
  const navigate = useNavigate();

  const setSession = useCallback((token: string, nextUser: UserSummary) => {
    setAccessToken(token);
    setUser(nextUser);
    persistSession(token, nextUser);
  }, []);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    persistSession(null, null);
  }, []);

  const updateUser = useCallback((partial: Partial<UserSummary>) => {
    setUser((prev) => {
      if (prev === null) return prev;
      const next = { ...prev, ...partial };
      try {
        localStorage.setItem(STORAGE_KEY_USER, JSON.stringify(next));
      } catch {
        /* silently ignore — D23 trade-off */
      }
      return next;
    });
  }, []);

  // Mantiene sincronizado el interceptor del apiClient con el estado actual del provider.
  // Se re-ejecuta al cambiar el token para que getAccessToken siempre cierre sobre el último valor.
  useEffect(() => {
    configureAuthInterceptor({
      getAccessToken: () => accessToken,
      onUnauthorized: () => {
        clearSession();
        navigate('/login', { replace: true });
      },
    });
  }, [accessToken, clearSession, navigate]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      accessToken,
      isAuthenticated: accessToken !== null,
      setSession,
      clearSession,
      updateUser,
    }),
    [user, accessToken, setSession, clearSession, updateUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** Hook de acceso al AuthContext. Lanza si se usa fuera del {@link AuthProvider}. */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (ctx === undefined) {
    throw new Error('useAuth debe usarse dentro de <AuthProvider>');
  }
  return ctx;
}
