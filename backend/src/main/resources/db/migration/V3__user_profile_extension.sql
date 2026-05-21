-- BloomTrade — Migración V3: extensión del perfil (HU-F04 Configurar perfil + HU-F20 Canal de notificación)
--
-- Agrega a app.users dos columnas requeridas por spec HU-F04+F20 §7.2:
--   - notification_channel  : preferencia del usuario para alertas (EMAIL/SMS/WHATSAPP).
--                             Default 'EMAIL' → usuarios registrados antes de esta migración
--                             reciben EMAIL automáticamente.
--   - tickers_of_interest   : subset de los 25 activos del catálogo (ARCHITECTURE.md §1)
--                             que el usuario quiere ver en el dashboard (HU-F18, Día 9).
--                             JSONB para queries eficientes con operadores @> y soporte
--                             nativo de índice GIN. Default '[]' = sin selección.
--
-- CHECK constraints:
--   - chk_users_notification_channel : la BD rechaza valores fuera del enum.
--   - chk_users_tickers_count        : máximo 25 elementos (catálogo completo). Si por bug
--                                       el código permite >25, la BD también rechaza.
--
-- Índice GIN sobre tickers_of_interest → necesario para HU-F19 Alertas de precio (post-MVP),
-- que consultará "qué usuarios tienen interés en ticker X". Crearlo ahora es trivial;
-- post-MVP con 10k usuarios sería pesado.
--
-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada a main.
-- Cualquier cambio futuro va en una V4, V5, etc. — NUNCA editar este archivo.

ALTER TABLE app.users
    ADD COLUMN notification_channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN tickers_of_interest  JSONB       NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE app.users
    ADD CONSTRAINT chk_users_notification_channel
        CHECK (notification_channel IN ('EMAIL', 'SMS', 'WHATSAPP'));

ALTER TABLE app.users
    ADD CONSTRAINT chk_users_tickers_count
        CHECK (jsonb_typeof(tickers_of_interest) = 'array'
               AND jsonb_array_length(tickers_of_interest) <= 25);

CREATE INDEX idx_users_tickers_of_interest
    ON app.users USING GIN (tickers_of_interest);

COMMENT ON COLUMN app.users.notification_channel IS 'Canal preferido de notificaciones (HU-F20)';
COMMENT ON COLUMN app.users.tickers_of_interest  IS 'Subset del catálogo de 25 activos para el dashboard (HU-F04+F18)';
