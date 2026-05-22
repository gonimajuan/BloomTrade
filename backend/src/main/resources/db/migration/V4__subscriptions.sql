-- BloomTrade — Migración V4: suscripción premium con Stripe (HU-F06)
--
-- Modela el ciclo de vida completo de una suscripción según spec HU-F06 §7:
--   - app.users.stripe_customer_id  : ID del Stripe Customer asociado al usuario (cus_...)
--   - app.subscriptions             : historia de suscripciones (una sola ACTIVE por usuario)
--   - app.stripe_webhook_events     : idempotencia de webhooks (spec §5.3.5 — Stripe puede
--                                     reintentar eventos legítimamente)
--
-- Invariante crítico: máximo UNA suscripción con status='ACTIVE' por usuario, enforced a nivel
-- BD vía índice único parcial (no solo en código).
--
-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada a main.
-- Cualquier cambio futuro va en V5, V6, etc. — NUNCA editar este archivo.

-- ─── 1) Extensión de app.users ──────────────────────────────────────────────
ALTER TABLE app.users
    ADD COLUMN stripe_customer_id VARCHAR(50);

CREATE INDEX idx_users_stripe_customer_id ON app.users (stripe_customer_id);

COMMENT ON COLUMN app.users.stripe_customer_id IS
    'ID del Stripe Customer (cus_*) asociado a este usuario (HU-F06). NULL hasta el primer checkout.';


-- ─── 2) app.subscriptions ───────────────────────────────────────────────────
CREATE TABLE app.subscriptions (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    stripe_customer_id       VARCHAR(50)  NOT NULL,
    stripe_subscription_id   VARCHAR(50)  NOT NULL,
    plan                     VARCHAR(15)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    current_period_start     TIMESTAMPTZ  NOT NULL,
    current_period_end       TIMESTAMPTZ  NOT NULL,
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT false,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_subscription_plan   CHECK (plan IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT chk_subscription_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'PAST_DUE')),
    CONSTRAINT uq_subscription_stripe_id UNIQUE (stripe_subscription_id)
);

CREATE INDEX idx_subscriptions_user_id ON app.subscriptions (user_id);
CREATE INDEX idx_subscriptions_status  ON app.subscriptions (status);

-- Invariante: máximo UNA suscripción ACTIVE por usuario (enforced en BD, no solo en código).
CREATE UNIQUE INDEX uq_one_active_subscription_per_user
    ON app.subscriptions (user_id)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE app.subscriptions IS
    'Historia de suscripciones premium (HU-F06). Una fila por intento; sólo una ACTIVE por usuario.';


-- ─── 3) app.stripe_webhook_events ───────────────────────────────────────────
-- Idempotencia: el unique constraint sobre stripe_event_id garantiza que el mismo webhook
-- procesado N veces resulte en exactamente 1 fila PROCESSED + (N-1) DUPLICATE rejections.
CREATE TABLE app.stripe_webhook_events (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    stripe_event_id      VARCHAR(80)  NOT NULL,
    event_type           VARCHAR(80)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    received_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at         TIMESTAMPTZ,
    error_message        TEXT,
    payload              JSONB        NOT NULL,
    CONSTRAINT uq_stripe_event_id UNIQUE (stripe_event_id),
    CONSTRAINT chk_webhook_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'DUPLICATE', 'ORPHAN'))
);

CREATE INDEX idx_webhook_events_type   ON app.stripe_webhook_events (event_type);
CREATE INDEX idx_webhook_events_status ON app.stripe_webhook_events (status);

COMMENT ON TABLE app.stripe_webhook_events IS
    'Tabla de idempotencia para webhooks de Stripe (HU-F06 §5.3.5). El UNIQUE sobre '
    'stripe_event_id es el corazón del mecanismo: el segundo insert del mismo evento falla y se '
    'mapea a STRIPE_WEBHOOK_DUPLICATE sin reprocesar el negocio.';
