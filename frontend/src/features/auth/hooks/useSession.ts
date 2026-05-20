// Hook de sesión INERTE — decisión usuario 2026-05-19 (plan HU-F01 §10 Q2): hoy siempre
// devuelve null porque HU-F01 no entrega login. HU-F02 lo conecta a la lectura del access
// token para que el guard de /register pueda redirigir a /dashboard si ya hay sesión activa.
export interface SessionState {
  token: string | null;
}

export function useSession(): SessionState {
  return { token: null };
}
