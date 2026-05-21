import { useQuery } from '@tanstack/react-query';
import { getMe } from '@/features/profile/api/profileApi';

export const PROFILE_QUERY_KEY = ['profile', 'me'] as const;

/** React Query: GET /me con caché. Stale time 60s (perfil cambia poco). */
export function useProfile() {
  return useQuery({
    queryKey: PROFILE_QUERY_KEY,
    queryFn: getMe,
    staleTime: 60_000,
  });
}
