package co.edu.unbosque.bloomtrade.notification;

import co.edu.unbosque.bloomtrade.notification.dto.AccountLockedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.CancellationScheduledEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderExecutedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderQueuedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderRejectedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.SubscriptionExpiredEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.SubscriptionPaymentFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomeEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomePremiumEmailCommand;

/**
 * Puerto del NotificationService. Interfaz conceptual {@code INotification} de ARCHITECTURE.md §5;
 * nombrada sin prefijo {@code I} por CONVENTIONS.md §5.3 (decisión D1 del plan HU-F01).
 *
 * <p>HU-F10 Lote C (D7 D-NOTIFIER-SPLIT): los 4 métodos de órdenes se renombraron con sufijo
 * {@code Buy} y se agregaron 4 contrapartes {@code Sell}. Razones: (a) wording diferente
 * ("cobrado" vs "recibido", "total a pagar" vs "producto neto") hace los templates lo
 * suficientemente distintos como para no compartir lógica; (b) firmas explícitas simplifican
 * test assertion (mock fácil de verificar); (c) Notifier es interfaz pequeña — 8 métodos de
 * orden + 7 transversales es manejable.
 */
public interface Notifier {

    /** Envía (asíncronamente) el email de bienvenida; su fallo no revierte el registro. */
    void sendWelcomeEmail(WelcomeEmailCommand command);

    /** Envía (asíncronamente) el OTP de MFA; su fallo no revierte el login paso 1. */
    void sendOtpEmail(OtpEmailCommand command);

    /** Envía (asíncronamente) el aviso de bloqueo temporal por intentos fallidos. */
    void sendAccountLockedEmail(AccountLockedEmailCommand command);

    // ─── HU-F06 — Suscripción premium con Stripe ────────────────────────────

    /** Bienvenida tras activación exitosa de suscripción premium. */
    void sendWelcomePremiumEmail(WelcomePremiumEmailCommand command);

    /** Confirmación de cancelación programada (Customer Portal v1.2). */
    void sendCancellationScheduledEmail(CancellationScheduledEmailCommand command);

    /** Aviso "tu suscripción premium ha terminado" — estado terminal CANCELLED. */
    void sendSubscriptionExpiredEmail(SubscriptionExpiredEmailCommand command);

    /** Aviso "tu pago de renovación falló" — estado terminal PAST_DUE. */
    void sendSubscriptionPaymentFailedEmail(SubscriptionPaymentFailedEmailCommand command);

    // ─── HU-F09 + HU-F10 — Órdenes Market BUY/SELL ──────────────────────────

    /**
     * Confirma al usuario que su orden de <b>compra</b> Market se ejecutó (precio real de
     * ejecución + comisión + total cobrado + nuevo saldo).
     */
    void sendOrderExecutedEmailBuy(OrderExecutedEmailCommand command);

    /**
     * Confirma al usuario que su orden de <b>venta</b> Market se ejecutó (precio real de
     * ejecución + comisión + producto neto acreditado + nuevo saldo + posición restante o
     * liquidación total).
     */
    void sendOrderExecutedEmailSell(OrderExecutedEmailCommand command);

    /**
     * Notifica que el mercado rechazó la orden de <b>compra</b>. NO se envía cuando el rechazo
     * es por {@code INSUFFICIENT_FUNDS} — decisión SPEC §9.2.
     */
    void sendOrderRejectedEmailBuy(OrderRejectedEmailCommand command);

    /**
     * Notifica que el mercado rechazó la orden de <b>venta</b>. Enfatiza que la posición y el
     * saldo NO fueron afectados. NO se envía para {@code SHORT_SELLING_NOT_ALLOWED} ni
     * {@code INSUFFICIENT_SHARES} (errores visibles en pantalla — SPEC F10 §9.2).
     */
    void sendOrderRejectedEmailSell(OrderRejectedEmailCommand command);

    /**
     * Notifica que la orden de <b>compra</b> no pudo procesarse por un error técnico
     * (Alpaca down post-retries, market data caído). Enfatiza que el saldo NO fue afectado.
     */
    void sendOrderFailedEmailBuy(OrderFailedEmailCommand command);

    /**
     * Notifica que la orden de <b>venta</b> no pudo procesarse por un error técnico. Enfatiza
     * que la posición y el saldo NO fueron afectados.
     */
    void sendOrderFailedEmailSell(OrderFailedEmailCommand command);

    /**
     * Notifica que la orden de <b>compra</b> fue aceptada por Alpaca pero quedó encolada porque
     * el mercado estaba cerrado (HU-F09 D29 emergente Lote H.5). Indica que el saldo SÍ se
     * redujo como reserva y que se ejecutará cuando abra el mercado.
     */
    void sendOrderQueuedEmailBuy(OrderQueuedEmailCommand command);

    /**
     * Notifica que la orden de <b>venta</b> fue aceptada por Alpaca pero quedó encolada (HU-F10
     * D9 D-SELL-QUEUED-RISK). Indica que la posición YA se decrementó optimistamente y que el
     * crédito al balance llegará al ejecutarse en la próxima apertura de mercado.
     */
    void sendOrderQueuedEmailSell(OrderQueuedEmailCommand command);
}
