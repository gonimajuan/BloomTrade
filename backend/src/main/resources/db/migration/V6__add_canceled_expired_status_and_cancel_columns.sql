-- BloomTrade — Migración V6: cancelación de órdenes (HU-F15)
--
-- Introduce los estados CANCELED y EXPIRED al enum OrderStatus + 4 columnas nuevas en
-- app.orders necesarias para el ciclo de vida completo de la cancelación:
--
--   - cancel_requested_at         : flag para polling-timeout (Q6 SPEC §5.2.1).
--                                    Coexiste con status=PENDING hasta que reconcile lazy v2
--                                    materialice la transición final a CANCELED.
--   - canceled_at                  : timestamp en que la orden quedó CANCELED.
--   - expired_at                   : timestamp en que la orden quedó EXPIRED (TIF day expirado).
--   - avg_buy_price_at_submission  : snapshot del avg_buy_price de la posición al momento de
--                                    submit SELL (D13). Necesario para re-INSERT correcto si
--                                    se cancela un SELL que liquidó la posición completa.
--
-- Backfill SELL legacy (R3 mitigation del plan.md): si hay SELLs pre-V6 sin el snapshot,
-- usar quoted_unit_price como fallback razonable.
--
-- Decisiones registradas en specs/HU-F15-cancelar-orden/plan.md:
--   D3  : agregar SOLO CANCELED + EXPIRED al enum (no IN_REVIEW/STOPPED — sobre-ingeniería).
--   D13 : avg_buy_price_at_submission preserva el costo histórico para re-INSERT.
--   D23 : todas las columnas en una sola migración aditiva, idempotente.
--   D25 : esta es V6 (no V8 como anticipaba un draft anterior; última migración es V5).
--   D26 : NO agregar alpaca_canceled_at — el timestamp se mapea directo a canceled_at nuestro.
--
-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada a main.

-- ─── Extender chk_order_status (4 valores → 6) ─────────────────────────────────
-- DROP + ADD es necesario para extender un CHECK constraint en Postgres. Idempotente
-- via IF EXISTS para reentries seguros.

ALTER TABLE app.orders DROP CONSTRAINT IF EXISTS chk_order_status;
ALTER TABLE app.orders ADD CONSTRAINT chk_order_status
    CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELED', 'EXPIRED'));

-- ─── 4 columnas nuevas ──────────────────────────────────────────────────────────
-- ADD COLUMN IF NOT EXISTS es idempotente: re-corridas son no-op.

ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMPTZ NULL;
ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS canceled_at         TIMESTAMPTZ NULL;
ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS expired_at          TIMESTAMPTZ NULL;
ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS avg_buy_price_at_submission NUMERIC(19, 4) NULL;

COMMENT ON COLUMN app.orders.cancel_requested_at IS
    'Flag para polling-timeout: cancelación solicitada por el usuario pero Alpaca no confirmó canceled en el polling 2s. Reconcile lazy v2 materializa la transición a CANCELED.';
COMMENT ON COLUMN app.orders.canceled_at IS
    'Timestamp en que la orden quedó CANCELED. NULL si status != CANCELED.';
COMMENT ON COLUMN app.orders.expired_at IS
    'Timestamp en que la orden quedó EXPIRED (TIF day expirado sin fill). NULL si status != EXPIRED.';
COMMENT ON COLUMN app.orders.avg_buy_price_at_submission IS
    'Solo SELL: snapshot del avg_buy_price de la posición al submit. Necesario para re-INSERT en cancel si la posición fue liquidada. NULL para BUY.';

-- ─── Backfill SELL legacy (R3) ─────────────────────────────────────────────────
-- Si hay SELLs queued pre-V6 sin snapshot, usar quoted_unit_price como fallback.
-- En MVP single-user este UPDATE típicamente afecta 0 filas (los SELLs ya ejecutados
-- son terminales y no necesitan cancel). Idempotente vía WHERE IS NULL.

UPDATE app.orders
   SET avg_buy_price_at_submission = quoted_unit_price
 WHERE side = 'SELL'
   AND avg_buy_price_at_submission IS NULL;

-- ─── Índice parcial para reconcile lazy v2 ─────────────────────────────────────
-- Acelera el barrido "buscar órdenes con cancel solicitado pendiente de materializar".
-- Parcial: solo indexa las filas con cancel_requested_at NOT NULL (la abrumadora mayoría
-- de filas en app.orders no tendrá este campo poblado).

CREATE INDEX IF NOT EXISTS idx_orders_cancel_requested_at
    ON app.orders (cancel_requested_at)
    WHERE cancel_requested_at IS NOT NULL AND status = 'PENDING';
