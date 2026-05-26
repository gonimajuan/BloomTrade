# Diagrama de Contexto — BloomTrade (C4 Nivel 1)

**Fuente:** `ARCHITECTURE.md` §1 (contexto), §8 (APIs externas), §11 (roles).
**Última actualización:** 2026-05-25 — post-cierre Sprint 2 (HU-F18+F17).

Este diagrama representa el sistema BloomTrade como una caja única, los actores humanos que lo usan y los sistemas externos con los que se integra. No muestra estructura interna — esa es competencia de los niveles 2 (Container) y 3 (Component).

---

## Diagrama

```mermaid
C4Context
  title BloomTrade — Contexto del Sistema (C4 Nivel 1)

  Person(investor, "Inversionista", "Crea órdenes Market sobre los 25 activos. Consulta su portafolio, saldo e historial.")
  Person(broker, "Comisionista", "Opera por cuenta de inversionistas asignados (lista cerrada).")
  Person(admin, "Administrador", "Configura comisiones, horarios de mercado y parámetros de sistema.")
  Person(legal, "Responsable Legal", "Consulta el registro de auditoría y reportes de cumplimiento.")
  Person(board, "Junta Directiva", "Visualiza KPIs y reportes ejecutivos.")

  System(bt, "BloomTrade", "Plataforma web de Day Trading sobre 5 mercados (NYSE, NASDAQ, LSE, TSE, ASX) — 25 activos.")

  System_Ext(alpaca, "Alpaca Markets", "Paper trading API v2 — ejecución de órdenes y datos de mercado.")
  System_Ext(polygon, "Polygon.io", "Market data API v3 (free tier) — alterno post-MVP.")
  System_Ext(stripe, "Stripe", "Procesa la suscripción premium (Checkout + webhooks).")
  System_Ext(twilio, "Twilio", "Envío de SMS y WhatsApp para notificaciones.")
  System_Ext(mail, "MailHog / SendGrid", "Captura SMTP local en dev; SendGrid opcional en prod.")

  Rel(investor, bt, "Crea órdenes, consulta portafolio", "HTTPS")
  Rel(broker, bt, "Opera por sus clientes asignados", "HTTPS")
  Rel(admin, bt, "Configura parámetros y horarios", "HTTPS")
  Rel(legal, bt, "Consulta auditoría", "HTTPS")
  Rel(board, bt, "Consulta KPIs", "HTTPS")

  Rel(bt, alpaca, "Submit Market Orders, snapshots de precio", "HTTPS / REST")
  Rel(bt, polygon, "Snapshots alterno (HU-F16 post-MVP)", "HTTPS / REST")
  Rel(bt, stripe, "Checkout Sessions, webhook signature verification", "HTTPS / REST")
  Rel(bt, twilio, "Envío de SMS/WhatsApp", "HTTPS / REST")
  Rel(bt, mail, "OTP, confirmaciones de orden, bienvenida", "SMTP")

  UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

---

## Convenciones

- **Actores humanos** (`Person`) — los 5 roles definidos en `ARCHITECTURE.md` §11.
- **BloomTrade** (`System`) — caja única; su descomposición está en `c4-container.md`.
- **Sistemas externos** (`System_Ext`) — APIs y servicios SaaS consumidos vía `IntegrationService` (excepción documentada: SMTP a MailHog/SendGrid pasa por Spring Mail nativo, no por adapter custom — `ARCHITECTURE.md` §8).

## Decisiones registradas

- La distinción `MailHog (dev)` vs `SendGrid (prod opcional)` se mantiene a este nivel porque ambos exponen la misma interfaz SMTP y son intercambiables vía `application.yml` (`STACK.md` §1).
- Polygon.io aparece aunque en MVP no se invoca: el adapter está implementado contra Alpaca Market Data, y Polygon es el reemplazo previsto en ESC-M3 (`ARCHITECTURE.md` §13).
