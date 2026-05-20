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
 * Estado de sesión en memoria (spec HU-F02 §12.3, decisión D12 del plan).
 *
 * <p>El access token NO se persiste en localStorage por requisito explícito de la spec — un XSS
 * que lograra ejecutarse no podría exfiltrar el token. Vive solo mientras la pestaña está abierta;
 * un reload obliga a volver a iniciar sesión (deuda aceptada por D18: el refresh token llegará en
 * la mini-HU post-MVP).
 */
export interface AuthContextValue {
  user: UserSummary | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  setSession: (token: string, user: UserSummary) => void;
  clearSession: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const navigate = useNavigate();

  const setSession = useCallback((token: string, nextUser: UserSummary) => {
    setAccessToken(token);
    setUser(nextUser);
  }, []);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
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
    }),
    [user, accessToken, setSession, clearSession],
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
