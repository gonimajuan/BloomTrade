package co.edu.unbosque.bloomtrade.audit;

/**
 * Puerto del AuditService (TAC-S4 — mantener registro de auditoría). Interfaz conceptual
 * {@code IAudit} de ARCHITECTURE.md §5; nombrada sin prefijo {@code I} por CONVENTIONS.md §5.3
 * (decisión D1 del plan HU-F01). La consumen los demás módulos para registrar eventos inmutables.
 */
public interface Auditor {

    /** Emite el evento de auditoría hacia el pipeline ELK. */
    void record(AuditEvent event);
}
