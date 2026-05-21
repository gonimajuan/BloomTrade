import { apiClient } from '@/lib/apiClient';
import type { UpdateProfileRequest, UserProfileResponse } from '@/types/api';

/** GET /api/v1/me — perfil del usuario autenticado. */
export async function getMe(): Promise<UserProfileResponse> {
  const { data } = await apiClient.get<UserProfileResponse>('/me');
  return data;
}

/**
 * PATCH /api/v1/me — actualización parcial. El caller debe omitir los campos que no quiere
 * enviar; enviar `null` literal en JSON dispara comportamiento indefinido del backend (D2 plan).
 */
export async function patchMe(
  payload: UpdateProfileRequest,
): Promise<UserProfileResponse> {
  const { data } = await apiClient.patch<UserProfileResponse>('/me', payload);
  return data;
}
