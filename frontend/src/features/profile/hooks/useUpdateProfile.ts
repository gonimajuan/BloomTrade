import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/context/AuthContext';
import { patchMe } from '@/features/profile/api/profileApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { UpdateProfileRequest, UserProfileResponse } from '@/types/api';
import { PROFILE_QUERY_KEY } from './useProfile';

/**
 * Mutación PATCH /me. Tras éxito:
 *  - Invalida la query del perfil para re-fetch (consistencia eventual).
 *  - Si cambió {@code nombreCompleto}, actualiza optimistamente el {@code AuthContext} para que
 *    el header del app refleje el nuevo nombre sin esperar al re-fetch (D12 plan).
 */
export function useUpdateProfile() {
  const queryClient = useQueryClient();
  const { updateUser, user } = useAuth();

  return useMutation<UserProfileResponse, ParsedError, UpdateProfileRequest>({
    mutationFn: async (payload) => {
      try {
        return await patchMe(payload);
      } catch (err) {
        throw parseError(err);
      }
    },
    onSuccess: (data) => {
      queryClient.setQueryData(PROFILE_QUERY_KEY, data);
      if (user && data.nombreCompleto !== user.nombreCompleto) {
        updateUser({ nombreCompleto: data.nombreCompleto });
      }
    },
  });
}
