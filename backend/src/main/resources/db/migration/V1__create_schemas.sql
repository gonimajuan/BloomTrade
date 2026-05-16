-- BloomTrade — Migración V1: schemas base
--
-- Crea los 3 schemas requeridos por STACK.md §4.1 y ARCHITECTURE.md §7.
-- 'app' lo provee Flyway vía 'spring.flyway.schemas=app' (allí vive flyway_schema_history),
-- pero se incluye con IF NOT EXISTS para ser autosuficiente si se ejecuta fuera de Flyway.
--
-- Regla CLAUDE.md #12: esta migración es INMUTABLE una vez mergeada a main.
-- Cualquier cambio futuro va en una V2, V3, etc. — NUNCA editar este archivo.

CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS config;
CREATE SCHEMA IF NOT EXISTS audit;

COMMENT ON SCHEMA app    IS 'Datos transaccionales del negocio (usuarios, órdenes, portafolios)';
COMMENT ON SCHEMA config IS 'Parámetros configurables por Admin (comisiones, horarios) — TAC-M2';
COMMENT ON SCHEMA audit  IS 'Espejo local de eventos auditables (fuente real: ElasticSearch)';
