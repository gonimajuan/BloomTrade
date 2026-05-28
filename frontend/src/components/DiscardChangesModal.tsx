import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';

interface DiscardChangesModalProps {
  open: boolean;
  title?: string;
  description?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Modal genérico de confirmación de descarte (HU-F04 §5.2.1).
 * Revamp Lote E: delegado al primitive {@link Modal} + {@link Button} (~80% menos código).
 */
export function DiscardChangesModal({
  open,
  title = '¿Descartar cambios sin guardar?',
  description = 'Tus cambios pendientes se perderán.',
  confirmLabel = 'Descartar',
  cancelLabel = 'Seguir editando',
  onConfirm,
  onCancel,
}: DiscardChangesModalProps) {
  return (
    <Modal isOpen={open} onClose={onCancel} title={title} size="sm">
      <p className="mb-6 text-sm text-slate-300">{description}</p>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" size="md" onClick={onCancel}>
          {cancelLabel}
        </Button>
        <Button variant="destructive" size="md" onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
