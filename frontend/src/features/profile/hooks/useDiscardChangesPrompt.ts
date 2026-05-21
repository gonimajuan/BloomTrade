import { useCallback, useEffect, useState } from 'react';

/**
 * Confirmación de descarte para formularios con cambios sin guardar (HU-F04 §5.2.1).
 *
 * <p>Modo manual: el caller llama a {@link request} cuando quiere proponer descartar (ej. en el
 * botón "Cancelar" con {@code formState.isDirty === true}). Adicionalmente intercepta
 * {@code beforeunload} para que el browser pregunte al cerrar/refrescar mientras hay cambios.
 *
 * <p>Nota: la versión "navegación SPA bloqueada por {@code useBlocker}" requiere migrar main.tsx
 * a {@code createBrowserRouter} (DataRouter). Quedó como deuda menor — D22 ajuste al plan.
 */
export function useDiscardChangesPrompt(isDirty: boolean) {
  const [isOpen, setIsOpen] = useState(false);
  const [onConfirmRef, setOnConfirmRef] = useState<(() => void) | null>(null);

  useEffect(() => {
    if (!isDirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [isDirty]);

  const request = useCallback((onConfirm: () => void) => {
    if (!isDirty) {
      onConfirm();
      return;
    }
    setOnConfirmRef(() => onConfirm);
    setIsOpen(true);
  }, [isDirty]);

  const confirmDiscard = useCallback(() => {
    setIsOpen(false);
    onConfirmRef?.();
    setOnConfirmRef(null);
  }, [onConfirmRef]);

  const cancelDiscard = useCallback(() => {
    setIsOpen(false);
    setOnConfirmRef(null);
  }, []);

  return { isOpen, request, confirmDiscard, cancelDiscard };
}
