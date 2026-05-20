-- BloomTrade — Migración V2: usuarios y balances (HU-F01 Registrarse)
--
-- Crea las tablas del schema 'app' requeridas por spec HU-F01 §7.2:
--   - app.users          : cuenta del inversionista (password_hash BCrypt 60 chars)
--   - app.user_balances  : saldo inicial USD 10,000 (NUMERIC(19,2), STACK.md §4.2)
--
-- Índice único case-insensitive sobre LOWER(email) → unicidad y búsqueda (spec §7.2).
-- CHECK constraints de enums → la BD rechaza valores fuera de rango (defensa en profundidad).
--
-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada a main.
-- Cualquier cambio futuro va en una V3, V4, etc. — NUNCA editar este archivo.

CREATE TABLE app.users (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(254) NOT NULL,
    password_hash       VARCHAR(60)  NOT NULL,
    nombre_completo     VARCHAR(100) NOT NULL,
    tipo_documento      VARCHAR(15)  NOT NULL,
    numero_documento    VARCHAR(15)  NOT NULL,
    telefono            VARCHAR(20)  NOT NULL,
    rol                 VARCHAR(20)  NOT NULL DEFAULT 'INVESTOR',
    estado              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    acepto_terminos_at  TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_users_rol      CHECK (rol IN ('INVESTOR', 'BROKER', 'ADMIN', 'LEGAL', 'BOARD')),
    CONSTRAINT chk_users_estado   CHECK (estado IN ('ACTIVE', 'BLOCKED', 'SUSPENDED')),
    CONSTRAINT chk_users_tipo_doc CHECK (tipo_documento IN ('CC', 'CE', 'PASAPORTE'))
);

CREATE UNIQUE INDEX idx_users_email_lower ON app.users (LOWER(email));
CREATE INDEX idx_users_rol    ON app.users (rol);
CREATE INDEX idx_users_estado ON app.users (estado);

CREATE TABLE app.user_balances (
    user_id      UUID PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
    balance      NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    currency     VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_nonneg CHECK (balance >= 0)
);

COMMENT ON TABLE app.users         IS 'Cuentas de inversionistas — HU-F01';
COMMENT ON TABLE app.user_balances IS 'Saldo del inversionista (USD); inicial 10000.00 al registrarse — HU-F01';
