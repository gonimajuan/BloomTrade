-- BloomTrade — Migración V5: trading (HU-F09 Orden de compra Market)
--
-- Introduce el andamio de trading + portafolio + comisiones según spec HU-F09 §7:
--   - app.orders                 : una fila por orden (idempotente vía client_order_id UNIQUE)
--   - app.positions              : una fila por (user_id, ticker) UNIQUE — quantity y avg_buy_price
--   - config.commission_rates    : tasas de comisión configurables (TAC-M2)
--
-- Invariantes críticos:
--   1) `app.user_balances.balance >= 0` ya enforced en V2; este SPEC lo respeta vía lock pessimistic.
--   2) Una sola fila ACTIVE por (user_id, ticker) en `app.positions` vía UNIQUE.
--   3) Idempotencia E2E vía `app.orders.client_order_id UNIQUE` + Alpaca acepta el mismo client_order_id (D14).
--   4) Una sola tasa de comisión activa por rol en `config.commission_rates` vía UNIQUE PARTIAL.
--
-- Decisiones registradas en specs/HU-F09-orden-compra-market/plan.md:
--   D9  D-MD-PROVIDER  : Alpaca-only para market data (Polygon diferido); no afecta DDL directo
--                        pero sí justifica que NUMERIC(19,4) acomode los precios mid-price calculados
--   D12 BigDecimal+HALF_UP : NUMERIC(19,4) para precios; comisión persistida con scale=2 lógico
--                            (se almacena como NUMERIC(19,4) pero la app la setScale antes)
--   D16 OrderStatus enum estrechado a 4 valores (PENDING/EXECUTED/REJECTED/FAILED)
--
-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada a main.
-- Cualquier cambio futuro va en V6, V7, etc. — NUNCA editar este archivo.

-- ─── app.orders ────────────────────────────────────────────────────────────────
-- Una fila por orden. status sigue la FSM PENDING → {EXECUTED, REJECTED, FAILED}.
-- Transiciones se auditan en ElasticSearch (D5: solo ES, sin tabla de transiciones).

CREATE TABLE app.orders (
    id                       UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID           NOT NULL REFERENCES app.users(id) ON DELETE RESTRICT,
    client_order_id          UUID           NOT NULL,
    ticker                   VARCHAR(10)    NOT NULL,
    side                     VARCHAR(4)     NOT NULL,
    type                     VARCHAR(10)    NOT NULL,
    quantity                 INTEGER        NOT NULL,
    quoted_unit_price        NUMERIC(19, 4) NOT NULL,
    quoted_commission        NUMERIC(19, 4) NOT NULL,
    quoted_total             NUMERIC(19, 4) NOT NULL,
    execution_unit_price     NUMERIC(19, 4),
    execution_total          NUMERIC(19, 4),
    status                   VARCHAR(10)    NOT NULL DEFAULT 'PENDING',
    alpaca_order_id          VARCHAR(80),
    error_code               VARCHAR(40),
    error_message            TEXT,
    submitted_at             TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_at              TIMESTAMPTZ,
    CONSTRAINT chk_order_side     CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_order_type     CHECK (type IN ('MARKET')),
    CONSTRAINT chk_order_status   CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED')),
    CONSTRAINT chk_order_quantity CHECK (quantity > 0),
    CONSTRAINT uq_orders_client_order_id UNIQUE (client_order_id)
);

CREATE INDEX idx_orders_user_id      ON app.orders (user_id);
CREATE INDEX idx_orders_status       ON app.orders (status);
CREATE INDEX idx_orders_ticker       ON app.orders (ticker);
CREATE INDEX idx_orders_submitted_at ON app.orders (submitted_at DESC);

-- UNIQUE parcial: alpaca_order_id NULL hasta que Alpaca responde con éxito.
-- Defensa contra reportar dos veces la misma ejecución en Alpaca.
CREATE UNIQUE INDEX idx_orders_alpaca_order_id
    ON app.orders (alpaca_order_id)
    WHERE alpaca_order_id IS NOT NULL;

COMMENT ON TABLE  app.orders                 IS 'Órdenes Market BUY/SELL del inversionista — HU-F09 (BUY) + HU-F10 (SELL)';
COMMENT ON COLUMN app.orders.client_order_id IS 'UUID generado en frontend. Idempotencia E2E: misma key 2x devuelve 1 orden';
COMMENT ON COLUMN app.orders.alpaca_order_id IS 'ID de Alpaca tras ejecución exitosa. NULL si PENDING/REJECTED/FAILED';
COMMENT ON COLUMN app.orders.quoted_unit_price  IS 'Precio mostrado al usuario en /orders/quote';
COMMENT ON COLUMN app.orders.execution_unit_price IS 'filled_avg_price reportado por Alpaca';

-- ─── app.positions ─────────────────────────────────────────────────────────────
-- Una fila por (user_id, ticker). Compras incrementan quantity y recalculan avg_buy_price.
-- Ventas (F10) decrementan; quantity puede llegar a 0 (decisión F10).

CREATE TABLE app.positions (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID           NOT NULL REFERENCES app.users(id) ON DELETE RESTRICT,
    ticker          VARCHAR(10)    NOT NULL,
    quantity        INTEGER        NOT NULL,
    avg_buy_price   NUMERIC(19, 4) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_position_quantity     CHECK (quantity >= 0),
    CONSTRAINT chk_position_avg_buy_pos  CHECK (avg_buy_price > 0),
    CONSTRAINT uq_positions_user_ticker  UNIQUE (user_id, ticker)
);

CREATE INDEX idx_positions_user_id ON app.positions (user_id);

COMMENT ON TABLE  app.positions               IS 'Posiciones del inversionista — una fila por (user_id, ticker)';
COMMENT ON COLUMN app.positions.avg_buy_price IS 'Promedio ponderado de compras (precio × cantidad / cantidad total)';

-- ─── config.commission_rates ───────────────────────────────────────────────────
-- TAC-M2 (diferir el enlace mediante configuración). HU-F30 expondrá UI admin para
-- cambiar tasas; HU-F09 solo LEE. Historial preservado vía valid_from/valid_to.

CREATE TABLE config.commission_rates (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    role            VARCHAR(20)    NOT NULL,
    percentage      NUMERIC(7, 4)  NOT NULL,
    valid_from      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_commission_role CHECK (role IN ('INVESTOR', 'BROKER', 'BROKER_USER')),
    CONSTRAINT chk_commission_pct  CHECK (percentage >= 0 AND percentage <= 1)
);

-- UNIQUE parcial: una sola fila activa (valid_to IS NULL) por rol.
CREATE UNIQUE INDEX uq_commission_active_per_role
    ON config.commission_rates (role)
    WHERE valid_to IS NULL;

COMMENT ON TABLE  config.commission_rates            IS 'Tasas de comisión por rol — TAC-M2; HU-F30 UI admin';
COMMENT ON COLUMN config.commission_rates.percentage IS 'Tasa decimal: 0.0200 = 2%';

-- Seed: tasa default INVESTOR 2% (ARCH §9). Aplicable inmediatamente sin reinicio.
INSERT INTO config.commission_rates (role, percentage) VALUES ('INVESTOR', 0.0200);
