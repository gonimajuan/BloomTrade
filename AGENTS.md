# AGENTS.md — BloomTrade

> Instrucciones para asistentes de código (Codex CLI, Claude Code, Cursor, Aider, etc.).
> Este archivo es **cross-agente**. Complementa a `CLAUDE.md` — pese al nombre, esas reglas
> son universales del proyecto, no específicas de Claude.

---

## Lo primero en cada sesión (sin excepción)

1. **Leé `CLAUDE.md` completo.** Es la constitución del proyecto. Las reglas inviolables
   #1–#24 aplican a vos también.
2. **Leé los documentos maestros en este orden:** `ARCHITECTURE.md` → `STACK.md` →
   `CONVENTIONS.md` → `ROADMAP.md`. No empieces a codear sin haberlos pasado.
3. **Leé la HU activa** (ver §"Trabajo activo" abajo): `specs/<HU>/SPEC.md` →
   `specs/<HU>/plan.md` → `specs/<HU>/tasks.md`.
4. **Confirmá branch + git status** antes de tocar nada.
5. **Saludá brevemente** describiendo: qué es el proyecto, qué arquitectura, qué HU/lote
   está activo. Esperá una indicación humana antes de codear (CLAUDE.md "Lo primero").

---

## Trabajo activo (actualizar al final de cada sesión)

| Campo | Valor |
|---|---|
| Branch | `feat/revamp-ui`. **Lotes A–F implementados, listo para HITO 6** (commit + push + PR firmado por humano). Working tree con 40 archivos frontend modificados + 8 archivos nuevos (5 primitives ui/ + GlassBackground + cn.ts + tests) + 1 borrado (OrderConfirmationToast — migrado a sonner) + STACK.md + docs/ui-revamp/PLAN.md + APRENDIZAJES Día 12. Mensaje commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-revamp-ui.txt`. |
| HU activa | **Revamp UI — cerrado técnicamente.** NO es HU formal (sin SPEC). Glassmorphism + violet accent + Space Grotesk + framer-motion + sonner. 6 primitives nuevos (Card, Button, Badge, Input, Modal, UserDropdown). 5 deps agregadas a STACK.md §3.2. Las 9 HUs MVP + F17 + F15 ya estaban cerradas — esta sesión es pulido visual previo a demo in-vivo. |
| Sprint | 2 funcional cerrado + Día 10 doc/infra cerrado + Día 11 F15 cerrado + Día 12 revamp UI cerrado. **NINGUNA HU pendiente.** **Estado tests:** backend `mvn verify` 410 (332 unit + 78 IT) intacto (cero cambios backend), frontend vitest **42/42** verde (+15 nuevos para primitives) + build **3787 módulos** (+410 vs baseline). Bundle JS 1024kb / **303kb gz** (+62kb gz vs pre-revamp, dentro del estimado +80kb). |
| Próximo paso | **HITO 6**: humano firma `git add -A && git commit -F C:\Users\juang\AppData\Local\Temp\bt-revamp-ui.txt && git push -u origin feat/revamp-ui && gh pr create` → squash a `main`. **Smoke visual manual recomendado pre-merge** (no bloqueante): `docker compose up -d --build` → recorrer las 6 pantallas autenticadas + login flow + cancel order con sonner toast. Tras merge: el MVP visual queda cerrado, listo para demo in-vivo. |
| Deuda viva (NO bloqueante) | ~~(1) Mini-HU `HU-F0X-token-rotation-logout`~~ **CERRADA Día 10 checkpoint 1**. (2) Tests IT webhooks Stripe con WireMock. (3) `ARCHITECTURE.md` §5 interfaces con prefijo `I`. (4) `useBlocker` requires DataRouter migration. (5) Generación auto de `frontend/constants/tickers.ts` desde OpenAPI. (6) `JWT_REFRESH_SECRET` eliminado de `.env.example`. ~~(7) JMeter ESC-R1+ESC-R2~~ **DIFERIDA post-MVP**. ~~(8) Reconciliation Alpaca-paper vs BD~~ **CERRADA Día 10 checkpoint 2**. (9) `clientOrderLocks` ConcurrentHashMap crece monotónico (D25 F09). (10) Polygon.io como alterno de market data (post-MVP). (11) **D28 F09 hardening**: check `ALPACA_BASE_URL` terminado en `/v2`. (12) **D29 F09 hardening**: ya implementado HU-F16 `pendingOrders[]`. ~~(13) HU-F09 orden encolada drift~~ **CERRADA Día 10 checkpoint 2**. (14) **HU-F10 D17 hardening (post-MVP)**: lock canónico `balances→positions` serializa SELLs concurrentes. ~~(15) HU-F10 D10~~ **CERRADA HU-F18 Lote E**. ~~(16) Dead code `sideNotYetImplemented`~~ **CERRADA HU-F18 Lote E**. ~~(17) HU-F16 D17 403 vs 401 sin JWT~~ **CERRADA Día 10 checkpoint 1**. (18) **HU-F16 D2 PERF**: `MarketDataAdapter` retry interno con backoff. ~~(19) Cache de market data~~ **CERRADA HU-F18 Lote A**. (20) **D-SPARKLINE-CACHE V2**. (21) **D-CACHE-STALE-ON-ERROR V2**. (22) **D-REDIS-HEALTH-BANNER**. (23) **D-ORDERS-UI-FILTERS-POSTMVP**. (24) **D-EQUITY-HISTORY-POSTMVP**. (25) **D-TOP-MOVERS-POSTMVP**. (26) **D-METRICS-CACHE-HIT-RATIO**. ~~(27) HU-F17 D-CANCELLED-STATUS-POSTMVP~~ **CERRADA HU-F15 Lote A** (OrderStatus extendido a 6 valores + V6). (28) **P1-4 audit Día 10**: validación server-side `max-quantity-per-order`. (29) **P2-x audit Día 10 pendientes**: `.env.example` cleanup (=#6), hook condicional OrderForm, centralizar formatters moneda. ~~(30) Reconciliation reversal canceled/rejected/expired~~ **CERRADA HU-F15 Lote C** (OrderReconciliationService v2). (31) **Pre-test health-check postgres test :5433** sigue post-MVP. (32) **NUEVO HU-F15 D27**: records con muchos campos rompen call sites positional; considerar factory methods nominales. (33) **NUEVO HU-F15 D35 — D-NO-TOAST-SYSTEM-FRONTEND**: frontend usa `window.alert` + `window.confirm` para feedback de cancel; reemplazar por toast global (`react-hot-toast` o `sonner`) en revamp UI post-F15. (34) **NUEVO HU-F15 D26 follow-up**: agregar columna `alpaca_canceled_at` (V7 futura) si emerge necesidad de distinguir "Alpaca confirmó" vs "BloomTrade transicionó". |

---

## Cómo continuar (post revamp UI → smoke visual + demo in-vivo)

**Estado actual (2026-05-27, cierre revamp UI técnico):**

- **Branch `feat/revamp-ui`** lista para HITO 6 (commit firmado por humano + push + PR).
- **Tests verdes:** backend `mvn verify` **410 intacto** (cero cambios backend — validación pasiva de arquitectura modular). Frontend vitest **42/42** verde (+15 nuevos: 4 Card + 7 Button + 4 Badge) + build **3787 módulos** (-1 borrado OrderConfirmationToast + nuevos primitives) · JS 1024kb / **303kb gz** (+62kb gz desde framer-motion + sonner + fontsource).
- **5 deps nuevas en STACK.md §3.2** + entrada de historial §14: `framer-motion` `sonner` `@fontsource-variable/space-grotesk` `clsx` `tailwind-merge`.
- **6 decisiones emergentes** documentadas en `docs/ui-revamp/PLAN.md` §9 (D1–D6). Notable: D3 (borrar OrderConfirmationToast.tsx 180 LOC reemplazado por sonner inline), D4/D5 (segmented pills pattern con `sr-only` inputs reusado 3 veces).
- **APRENDIZAJES.md sección "Día 12 — Revamp UI"** con 8 reflexiones técnicas + meta (SDD vs revamp visual, glassmorphism como decisión arquitectónica, lotes como unidad de validación, borrar > refactorear, segmented pills pattern emergente, tipografía como ROI alto, twMerge para primitives, 0 backend changes como validación pasiva).

**Lo primero del humano (HITO 6 pre-merge):**

1. **Smoke visual manual recomendado** (~15 min, no bloqueante):
   ```powershell
   docker compose up -d --build frontend
   # abrir http://localhost en navegador
   ```
   - **Login flow:** ir a `/login` → ver `❖ BloomTrade` hero violet + Card glass-elevated con orbes drifting detrás · login con MFA → ver MFAVerifyPage con mismo chrome.
   - **AppHeader:** sticky glass + pill active state en navegación + avatar dropdown con iniciales `JG` y badge del rol.
   - **Dashboard:** EquityCard hero glass + grid 5×5 mercados con pills violet · click en ticker → SparklinePanel grande aparece abajo con tooltip glass dark · RecentOrdersWidget con Badge variants para status.
   - **Portfolio:** BalanceCard glass + PositionsTable dark con hover violet · PendingOrders panel con Badge warning · Cancel button → Modal glass + sonner toast top-right (cierra deuda viva #33 D35).
   - **Trade:** OrderForm con BUY/SELL pills emerald/rose · request quote → panel desliza desde derecha (AnimatePresence) · confirm → toast sonner top-right (success/info).
   - **Profile:** 3 Cards glass stagger entry · canal de notif como pills violet · tickers de interés como pills glass clickeables.
   - **Premium:** Plan Anual destacado con shadow-glow-violet + Badge "Más popular".

2. **Commit + push + PR**:
   ```powershell
   git add -A
   git commit -F C:\Users\juang\AppData\Local\Temp\bt-revamp-ui.txt
   git push -u origin feat/revamp-ui
   gh pr create  # o desde GitHub
   ```
   Squash and merge a `main`.

**Próxima sesión (post-merge): MVP visual cerrado, listo para demo in-vivo**

No quedan HUs ni revamps pendientes. El proyecto está en estado **demo-ready**:

- **Backend**: MVP funcional cerrado (9 HUs §2.1 + F17 + F15). `mvn verify` 410 verde.
- **Frontend**: revamp visual completo, dark glass + violet accent + framer animations.
- **Docs SDD**: 7 specs/HU-FXX/{SPEC,plan,tasks}.md + docs/ui-revamp/PLAN.md.
- **APRENDIZAJES.md**: 12 días de reflexiones (~80 bullets técnicos).
- **AGENTS.md**: handoff vivo, este bloque + 6 históricos.

**Trabajo opcional post-MVP** (en orden ROI):
- Deudas vivas remanentes (ver tabla "Trabajo activo"): #2 IT Stripe webhooks · #4 useBlocker migration · #6 .env.example cleanup · #9 clientOrderLocks GC · #14 lock canónico balances→positions PERF · #18 retry adapter con backoff · #20-#26 D-XXX-POSTMVP markers.
- Si el profesor pide pulir UX adicional: pages restantes (los OTPInput/Countdown/ResendButton internos del MFAVerifyPage quedaron sin refactor — ver Lote C).
- Si SonarCloud queda flagueando: revisar warnings nuevos del revamp (esperado: ninguno crítico).

---

## Cómo continuar (post HU-F15 → revamp UI con frontend-design) [HISTÓRICO — completado en sesión 2026-05-27]

**Estado actual (2026-05-27, cierre HU-F15 técnico):**

- **Branch `feat/HU-F15-cancelar-orden`** lista para HITO 6 (commit firmado por humano + push + PR).
- **Tests verdes:** backend `mvn verify` **410 (332 unit + 78 IT)**, +47 vs baseline pre-F15. Frontend vitest **27/27** + build **3377 módulos** (+2 nuevos: `useCancelOrder` + `CancelOrderButton`).
- **Migración V6 aplicada** en `bloomtrade` (DB principal): `chk_order_status` extendido a 6 valores + 4 columnas nuevas (`cancel_requested_at`, `canceled_at`, `expired_at`, `avg_buy_price_at_submission`) + índice parcial `idx_orders_cancel_requested_at`.
- **11 decisiones emergentes** durante implementación documentadas en `plan.md` §2.4 (D25–D35) — patrón estable confirmado, F15 es la HU con más emergentes del proyecto por su complejidad (polling + reconcile v2 + drift inline + 4 outcomes + idempotency 2 paths).
- **APRENDIZAJES.md sección "Día 11 — HU-F15"** con 8 reflexiones técnicas + meta (sealed types vs excepciones, reconcile v2 extensión aditiva, drift inline DRY, idempotencia implícita vs explícita, RACE_FILLED realidad-del-broker, migración aditiva, records y call sites, smoke manual no-opcional).

**Lo primero del humano (HITO 6 pre-merge):**

1. **Commit + push + PR**:
   ```powershell
   git add -A
   git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f15.txt
   git push -u origin feat/HU-F15-cancelar-orden
   gh pr create  # o desde GitHub
   ```
   Squash and merge a `main`.

2. **Smoke E2E manual recomendado pre-merge** (no bloqueante — IT cubren el flujo backend completo):
   - Pre-setup: `docker compose up -d --build backend frontend`.
   - Generar orden PENDING+alpacaOrderId: BUY Market en horario pre-mercado COL (3–8:30am) → Alpaca devuelve `accepted` → queda PENDING.
   - `/portfolio` → sección "Órdenes en cola" → click "Cancelar" → confirm dialog → `window.alert("Orden cancelada — USD X restaurados")` → fila desaparece + `BalanceCard` actualiza.
   - Verificar: MailHog (`localhost:8025`) email "Tu orden de compra fue cancelada", Kibana (`localhost:5601`) events ORDER_CANCEL_REQUESTED + ORDER_CANCELED, Alpaca dashboard orden `canceled`.
   - Plan completo de 10 smokes documentado en sesión actual Lote E reporting.

**Próxima sesión (post-merge): revamp UI completo con `frontend-design` skill**

**No es una HU formal** — es pulido visual general del producto previo a evaluación in-vivo. La memoria del proyecto sugiere `frontend-design` skill disponible para diseño distintivo (alejarse del aesthetic genérico AI).

**Áreas candidatas para revamp**:

| Componente | Estado actual | Áreas de mejora |
|---|---|---|
| `LoginPage` | Funcional, layout simple | Branding BloomTrade, hero opcional, color palette |
| `OrderForm` + `OrderQuotePanel` | Funcional con side-aware | Visual consistency, animaciones quote → confirm |
| `BalanceCard` | RefreshCw button + relative time | Color hierarchy, P&L visual emphasis |
| `PositionsTable` | Tabla con P&L color-coded | Density, hover states, mobile responsive |
| `DashboardPage` | EquityCard + grid 25 tickers + SparklinePanel + RecentOrdersWidget | Layout overall, sparkline grande UX |
| `RecentOrdersWidget` + `PendingOrdersPanel` | Funcional con HU-F15 cancel | Botón Cancel destructive visual + transición canceled fila |
| **Cancel UX (NUEVA F15)** | `window.confirm` + `window.alert` (D35) | **Reemplazar por toast global** (`sonner` o `react-hot-toast`) + modal de confirmación custom |
| `AppHeader` | Links Dashboard/Portfolio/Trade/Premium + logout | Branding + user menu polished |
| Theming general | Tailwind defaults + slate/emerald/rose | Paleta custom + tipografía consistente |

**Decisiones a tomar al arrancar revamp**:
- Paleta de colores (mantener emerald/rose o pivotear).
- Tipografía (system fonts o agregar Inter/Roboto).
- Layout grid (max-width, gutters, breakpoints).
- Animaciones (sutiles vs notables).
- Mobile responsive (priority bajo para MVP — single desktop).
- Toast system: `sonner` (recomendado en STACK.md frontend-design? verificar) vs `react-hot-toast` vs `radix-ui/toast`.

**Pre-requisito al arrancar**: PR de HU-F15 mergeado a `main`. Crear branch nueva `feat/revamp-ui` desde main actualizado.

**Cronograma estimado revamp UI**: 1 día completo. Si va adelantado, P2 cleanup express del audit Día 10 (#6 + #11/#28 + #29 = ~30 min) como cierre.

---

## Cómo continuar (post Día 10 checkpoint 2 → HU-F15 cancelar orden) [HISTÓRICO — completado en sesión 2026-05-27]

**Estado actual (2026-05-26, cierre Día 10 doc + infra + bug fixes):**

- 2 commits del Día 10 firmados por humano (ce7dfa2 + e6f59a1 + checkpoint 2 firmado al final de la sesión actual). Tests verdes: backend mvn verify 363 (294 unit + 69 IT), frontend vitest 27/27 + build 3375 módulos.
- **Sprint 2 funcional cerrado** — las 9 HUs del MVP §2.1 + bonus F17 implementadas. ÚNICA HU promovible no implementada: **HU-F15 cancelar orden** (ROADMAP §3.4 stretch goal #1).
- **Decisiones explícitas del Día 10** (todas en memoria):
  - JMeter ESC-R1+R2 → diferido a post-MVP (`load-tests/` ya commiteado para retomar).
  - Sprint Reviews/Retros (Sprint 1 + Sprint 2) → **descartados**. Profesor evalúa demo en vivo, no docs reflexivos. Memoria `project_sprint_reviews_descartados.md`.
  - Informe Final del PDF del curso → **descartado** por misma razón.
- **APRENDIZAJES.md sección Día 10** actualizada con 7 reflexiones técnicas (audit cruzado pattern, falsos positivos del Explore, bugfix vs SDD para deudas registradas, bug bars shape oculto por stubs, drift Alpaca↔BD lazy reconcile, sparkline mini fracaso por densidad, postgres test :5433 cayó como falso positivo).
- **Deudas vivas:** ver tabla Trabajo activo. CERRADAS en este Día: #1, #7, #8, #13, #15, #16, #17, #19. Nuevas: #28-#31.

**Lo primero del usuario (HITO 0 pre-HU-F15):**

1. **Confirmar branch base** — el bundle Día 10 está en `feat/HU-F18-F17-dashboard-historial` (¡no es F18+F17 puro, contiene también todo el Día 10!). Si se mergeó a `main` ya: arrancar `feat/HU-F15-cancelar-orden` desde main actualizado. Si no: el humano decide si mergear primero o arrancar la rama desde el último commit.
2. **Re-validar smokes acumulados del Día 10** si aún no se hizo (no bloqueante, pero buen sanity check antes de HU-F15):
   - Banner sesión expirada (mini-HU token-rotation).
   - Reconcile lazy: hacer orden Market hoy con mercado abierto, debe quedar EXECUTED directo. Para validar el lazy real, esperar al cierre + colocar otra orden Market → debe quedar PENDING en BD → al reload del dashboard mañana, debe materializarse a EXECUTED automáticamente.
   - Dashboard: panel grande de sparkline + grid clickeable funcionando.

**Lo primero de la sesión nueva (Paso 1 SDD de HU-F15):**

Leer en orden:
1. `CLAUDE.md` (siempre).
2. `ARCHITECTURE.md` §9 "Estados de una orden" — actualmente hay `Pendiente, Enviada, En Ejecución, Ejecutada, Cancelada` listadas en el modelo de dominio, pero **el enum `OrderStatus` en código solo tiene 4 valores** (PENDING, EXECUTED, REJECTED, FAILED — deuda #27 lo registra). HU-F15 introduce CANCELED.
3. `ROADMAP.md` §3.4 si describe F15 — el SPEC original puede tener acceptance criteria.
4. `AGENTS.md` (este archivo).

**Preguntas que el SPEC HU-F15 debe responder (resolver con el usuario antes de codear):**

| Pregunta | Por qué importa |
|---|---|
| ¿Qué órdenes son cancelables? | Solo PENDING+alpacaOrderId (queued en Alpaca, sin filled todavía) es lo natural. El path "orden en vuelo dentro del placeOrder polling" es race condition compleja — probablemente fuera de scope. |
| ¿Refund inmediato del balance en BUY queued? | Cuando se cancela una BUY queued, el balance fue debited optimistamente con `quoted_total` (D29 F09). La cancelación debe revertir ese débito (CREDIT). Reusa la lógica de `OrderReconciliationService` v2 (reversal canceled). |
| ¿Revert position en SELL queued? | Espejo: SELL queued decrementó posición optimistamente (D-SELL-QUEUED-RISK F10). Cancelación → re-INSERT/UPDATE position. |
| ¿Endpoint REST? | Conventional Commits sugiere `DELETE /api/v1/orders/{id}` (RESTful), pero `POST /api/v1/orders/{id}/cancel` es más explícito sobre la acción. Decidir. |
| ¿Idempotencia? | Como `placeOrder` con `clientOrderId`. Si el usuario hace doble-click en "Cancelar", la 2da llamada debe ser 200 OK no 4xx. |
| ¿UI: dónde aparece el botón "Cancelar"? | Naturales: (a) `PendingOrdersPanel` del portfolio con botón por fila. (b) `RecentOrdersWidget` del dashboard si es PENDING. (c) Página dedicada `/orders` (deuda #23 — está post-MVP). |
| ¿Reconcile lazy v2 dentro del scope de F15? | El `OrderReconciliationService` v1 actual NO maneja `canceled/rejected/expired` desde Alpaca. F15 obliga a v2 porque después de cancelar via Alpaca, el reconcile debe propagar el CANCELED a BD. Probable scope: F15 incluye v2. |
| ¿Alpaca cancel API? | `DELETE /v2/orders/{id}` de Alpaca. Devuelve `canceled` async — el adapter debe polling-igual-que-place o trust Alpaca. Definir. |
| ¿Notificación + audit? | Email "tu orden de X fue cancelada" + audit event `ORDER_CANCELED`. Reusar pattern de `OrderExecutedEvent`. |
| ¿Estados de origen permitidos? | Solo `PENDING+alpacaOrderId` (queued). `EXECUTED` ya no se puede cancelar. `REJECTED/FAILED` ya son terminales sin Alpaca activa. |

**Esquema de bundle propuesto (refinable en SPEC):**

| Lote | HITO | Contenido aproximado |
|---|---|---|
| A | 1 | Backend: `OrderStatus` agregar `CANCELED` + migración Flyway V8 (extender CHECK constraint). `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)`. Tests unit. |
| B | 2 | Backend: `TradingService.cancelOrder(userId, orderId)` con reversal de balance/position. `CancelOrderResult`. Tests unit + IT (PostgreSQL). |
| C | 3 | Backend: `OrderReconciliationService` v2 maneja `canceled/rejected/expired` de Alpaca. Si una PENDING es canceled en Alpaca (timeout TIF day, etc.), también reverse. Tests. |
| D | 4 | Frontend: `useCancelOrder` hook + botón "Cancelar" en `PendingOrdersPanel` con confirmación. Toast de éxito. Reusar `messages.es.ts`. |
| E | 5 | Tests IT cross-user (`/orders/{id}/cancel` con otro user → 403/404), `mvn verify` completo. |
| F | 6 | `plan.md` §2.4 emergentes + APRENDIZAJES.md sección "Día 11 — HU-F15" + AGENTS.md handoff + commit message en temp. |

**Después de HU-F15 cerrada:** revamp UI con Claude Design. **Es una sesión separada** (memoria sugiere `frontend-design` skill disponible para diseño distintivo). El usuario tiene la decisión.

**Stretch (si todo va adelantado):**
- P2 cleanup express del audit Día 10: `.env.example` (#6) + hook condicional OrderForm (#29) + ALPACA_BASE_URL hardening (#11/#28). ~30 min total.
- Pre-test health-check postgres :5433 (#31). ~10 min.

---

## Cómo continuar (post Día 10 checkpoint 1 → checkpoint 2)

**Estado actual (2026-05-25, cierre Día 10 checkpoint 1):**

- Working tree con 17 modificados (4 backend + 13 frontend) + 14 nuevos (1 backend, 1 frontend, 6 `docs/diagrams/`, 6 `load-tests/`). Mensaje commit gigante en `C:\Users\juang\AppData\Local\Temp\bt-day10-polish.txt` (P6 ruta completa literal).
- Tests verdes: backend `mvn verify` 357 (288 unit + 69 IT), frontend vitest 27/27 + `npm run build` 3375 módulos.
- **Decisión clave registrada:** JMeter diferido formalmente a post-MVP (memoria `project_jmeter_post_mvp.md`). Foco vira a pulir flujo single-user.
- **Audit cruzado** del flujo single-user (deuda viva + Explore agent + DoD SPECs) — 6 IDs evaluados, 5 cerrados con código, 1 verificado falso positivo:
  - P0-1 ✅ `AuthenticationEntryPoint` → 401 AUTH_REQUIRED (cierra deudas #1 + #17 + D17 F16+F21 + D-T5.2 F18).
  - P0-2 ✅ Banner contextual del cooldown en MFAVerifyPage + callback `onResendError` en ResendButton.
  - P1-1 ✅ `error.code` + `traceId` visibles en banners (RecentOrdersWidget, OrderQuotePanel, PortfolioPage).
  - P1-2 ✅ `isFetching` propagado a PositionsTable + PendingOrdersPanel con `opacity-60` + `aria-busy`.
  - P1-3 ✅ Timestamps absolutos con timezone vía nuevo `lib/dateFormat.ts` (Intl.DateTimeFormat es-CO con `timeZoneName: 'short'`).
  - P1-5 ✅ Verificado **falso positivo** del Explore agent (flujo previene escenario "orden huérfana" por diseño en `TradingService:275-289` + `PortfolioService.debit` lock pessimistic).

**Aprendizaje meta (a registrar en APRENDIZAJES Día 10):** Los agentes Explore son útiles para escanear amplio pero pueden generar falsos positivos confundiendo flujo. P1-5 (orden huérfana) y parte de P0-2 (countdown) eran ya inexistentes/implementados. **Patrón:** delegar barrido a Explore, pero verificar antes de actuar — especialmente cuando el agent describe orden de operaciones del código.

**Lo primero del humano (HITO 6 pre-merge del checkpoint 1):**

1. **Smokes visuales recomendados** (~5 min):
   - login → esperar 15 min → click endpoint → debería ir a /login con banner ámbar "Tu sesión expiró".
   - Borrar accessToken de localStorage en DevTools → recargar /portfolio → /login SIN banner (entrada anónima nueva).
   - MFA → presionar resend dos veces → banner ámbar "Espera unos segundos…" + botón "Reenviar en Ns".
   - Provocar 4xx en /trade → banner muestra "Código: XYZ · traceId: …".
   - /portfolio durante refetch → tabla con `opacity-60`.
   - Abrir `docs/diagrams/c4-context.md` en VS Code con Mermaid preview → render OK.

2. **Commit + Push + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-day10-polish.txt` (ruta completa P6).
   - `git push origin feat/HU-F18-F17-dashboard-historial`
   - `gh pr create` o desde GitHub. Squash a `main`.

**Cómo arrancar Día 10 checkpoint 2 (tras merge):**

Opciones (no exclusivas, en orden de ROI académico):

1. **APRENDIZAJES.md sección Día 10** (~30 min): reflexiones técnicas + meta sobre audit cruzado, falsos positivos del Explore agent, decisión consciente de saltar SDD para deuda registrada, decisión post-MVP de JMeter. **Alto peso académico** (memoria `feedback_specs_sobre_bitacora.md` indica que SPECs > bitácora, pero APRENDIZAJES sigue siendo entregable).
2. **P1-4 backend** (defense-in-depth max-quantity): ~15 min, toca `TradingService.quote/placeOrder`. Cierra deuda #28.
3. **P2-1 docs** (`ARCHITECTURE.md` §5 quitar prefijo `I` de interfaces): ~20 min. Cero código. Peso académico.
4. **Sprint Reviews/Retros** (`docs/sprints/sprint-{1,2}-{review,retro}.md`): ~1-2h por sprint. Material para Informe Final.
5. **Informe Final del PDF del curso**: estructura + secciones técnicas + demo grabada (composición humana + IA puede preparar estructura/secciones objetivas).
6. **Lote P2 cleanup** (P2-3 + P2-4 + P2-5 + P2-6): ~30 min total, deudas chicas.

**Stretch:** HU-F15 cancelar orden (única HU promovible que no se atacó — `OrderStatus` aún sin CANCELLED, deuda #27).

---

## Cómo continuar (post HU-F18+F17 → Día 10 estabilización) [HISTÓRICO — checkpoint 1 cierra parcialmente]

**Estado actual (2026-05-25, cierre de sesión implementación HU-F18+F17):**

- HU-F18+F17 implementación completa en branch `feat/HU-F18-F17-dashboard-historial`. **346 tests verdes (277 unit + 69 IT)**, 0 regresiones. Frontend `npm run build` verde (3374 módulos).
- HITOs 1–5 ✅ (backend + tests IT + frontend build + verify completo + cleanup deudas). HITO 4 smoke visual humano pendiente (no bloqueante — sin tests UI por [[feedback-coverage-vs-velocidad]]). HITO 6 ⏸️ commit + push + PR firmado por humano.
- Decisiones emergentes en `plan.md` §2.4: **D25** (`Map.copyOf` no preserva orden — `Collections.unmodifiableMap(LinkedHashMap)` fix), **D26** (`RedisConfig` chocaba con Spring Boot auto-config — borrado entero, auto-config `stringRedisTemplate` cubre todo), **D27** (skip `OrderSpecificationsTest` puro — IT cubre los predicados via HTTP real, mocks Criteria API serían frágiles).
- SPEC sin bump v1.1 — todas las decisiones emergentes son implementation-detail.
- APRENDIZAJES.md con sección Día 9 (9 reflexiones técnicas + meta sobre cierre Sprint 2 funcional MVP).
- **3 deudas vivas cerradas en este bundle**: #19 cache Redis (Lote A), #16 dead code `sideNotYetImplemented` (Lote E), #15 filtro SELL dropdown por posiciones (Lote E). Efecto compuesto del contexto.

**Lotes HU-F18+F17 cerrados (A–F):**

| Lote | HITO | Resumen | Tests añadidos |
|---|---|---|---|
| A | 1 ✅ | Cache Redis + bars Alpaca. 3 DTOs (`AlpacaBar`, `AlpacaBarsResponse`, `IntradayBar`). `MarketDataAdapter.getIntradayBars(ticker)` con `@Retry` + fallback. `CachedMarketDataAdapter` decorator (singular + batch `getLatestPrices` con multi-get Redis + fan-out paralelo de misses sobre `marketDataExecutor` de F16). **D26 emergente**: borrar `RedisConfig.java` — colisión con Spring Boot auto-config; `StringRedisTemplate` auto-config es upcast natural a `RedisTemplate<String,String>`. | 13 unit (9 cache + 4 bars) |
| B | 2 ✅ | Dashboard backend. 4 DTOs (`TickerDashboardDto`, `MarketGroupDto`, `AccountEquityDto`, `DashboardSnapshotResponse`). `BarsOrchestrator` (análogo a `MarketDataOrchestrator` F16, retorna `List<IntradayBar>` con empty default). `PortfolioService.getAccountEquity(userId, prices)` con D-EQUITY-PARTIAL (sum parcial si algunos prices null). `DashboardService` orquesta cache + fan-out bars + equity. `DashboardMapper` (scale=2 + dayChangePct con div-zero safe). `DashboardController` `@AuthenticationPrincipal AuthenticatedUser principal`. **D25 emergente**: `AllowedTickers.byMarket()` no preservaba orden — F18 fue la primera HU que itera (F04+F20 solo usaba `contains`). Fix: `Collections.unmodifiableMap(LinkedHashMap)`. | 17 (13 unit + 4 IT con WireMock + Redis real) |
| C | 3 ✅ | Order History backend. `OrderRepository extends JpaSpecificationExecutor<Order>`. `OrderSpecifications` factory con `byUser/byTicker/bySide`. 3 DTOs (`OrderHistoryResponse`, `OrderHistoryDto`, `PaginationDto`). `OrderHistoryService` con composición dinámica de Specs. `OrderHistoryController` (paginación + filtros + cap size 100). Handler `MethodArgumentTypeMismatchException` → 400 `INVALID_REQUEST_PARAMETER` (key i18n agregada). **D27 emergente**: skip `OrderSpecificationsTest` puro — IT cubre los predicados via HTTP real con datos seedeados, mocks Criteria API serían frágiles. Fix `Instant`→`Timestamp` en `jdbc.update` (PG no infiere tipo). | 17 (4 service unit + 6 mapper unit + 7 IT controller) |
| D | 4 ✅ | Frontend dashboard. `types/api.ts` +8 interfaces. 2 API wrappers (`dashboardApi`, `ordersApi`). 2 hooks polling 30s + `refetchIntervalInBackground:true`. 5 components (`Sparkline` recharts 100×30 sin animaciones/tooltip, `TickerRow`, `TickerGrid` responsive 1/2/5 cols, `EquityCard` P&L color-coded + relative time, `RecentOrdersWidget` con details/summary + empty state CTA). **Reuso `MarketDataBanner` del módulo portfolio** (no duplicar). `pages/DashboardPage.tsx` reemplaza placeholder. `AppHeader` +link "Dashboard" primero. `messages.es.ts` +`dashboardMessages`. `npm run build` verde 3374 módulos. | 0 (skipped per [[feedback-coverage-vs-velocidad]]) |
| E | 5 ✅ | `DashboardControllerIT` +3 IT (cross-user equity no leak con WireMock verify, sin JWT → 403 con comentario D17 F16+F21, cache TTL expira manual → segunda call golpea Alpaca). **Limpieza 3 deudas vivas**: (#16) `InvalidSideException.sideNotYetImplemented()` factory + property `SIDE_NOT_YET_IMPLEMENTED` + javadocs limpiados en 4 lugares. (#15) `TickerDropdown` +prop `ownedTickers?: ReadonlySet<string>`; `OrderForm` consume `usePortfolioPositions` cuando side=SELL → dropdown filtrado + estado "sin posiciones" con banner amber. `mvn verify` completo: **346 tests verdes (277 unit + 69 IT)**, 0 regresiones. Frontend 27/27 vitest verde + npm run build verde. | 3 IT |
| F | 6 ⏸️ | `plan.md` §2.4 con D25–D27. `APRENDIZAJES.md` sección "Día 9 — HU-F18+F17". `AGENTS.md` handoff actualizado (este bloque). Commit message en `C:\Users\juang\AppData\Local\Temp\bt-hu-f18-f17.txt`. SPEC NO bump (decisiones implementation-detail). | — |

**Bugs encontrados y arreglados durante implementación HU-F18+F17:**

1. **D25 — `Map.copyOf` no preserva orden**. `DashboardServiceTest#getSnapshot_groupsInCorrectOrder_NYSE_NASDAQ_LSE_TSE_ASX` falla porque Java spec dice iteración "unspecified". Fix: `Collections.unmodifiableMap(LinkedHashMap)` en `AllowedTickers.buildByMarket`. Bug latente de 8 sesiones (F04+F20 solo usaba `contains`, F18 fue la primera HU que itera por orden).
2. **D26 — Bean name collision Redis**. `BeanDefinitionOverrideException: stringRedisTemplate` en `@SpringBootTest`. Spring Boot `RedisAutoConfiguration` ya provee `StringRedisTemplate` (subclase de `RedisTemplate<String,String>`). Fix: borrar `RedisConfig.java` — cero líneas de config necesarias.
3. **D27 — Skip `OrderSpecificationsTest`**. Tasks.md proponía test puro con mocks de `Root`/`CriteriaBuilder` — frágiles + duplican lo que el IT real prueba. Decisión consciente: skip + documentar. Patrón disciplinado para próximas Specifications.
4. **`Instant` → `Timestamp` en `jdbc.update`**: PG JDBC driver no infiere tipo SQL para `java.time.Instant`. Fix trivial: `Timestamp.from(instant)`. No registrado como D — bug de plumbing.

**Lo primero del humano (HITO 6 pre-merge):**

1. **Smoke visual** (no bloqueante — usuario lo hará "después"):
   - `docker compose up -d --build` (rebuild para que frontend tome los nuevos archivos).
   - Login → `/dashboard`. Esperado: EquityCard arriba con USD formato es-CO + P&L color-coded; grid 5×5 (NYSE/NASDAQ/LSE/TSE/ASX) con sparklines minimal verde/rojo/gris; widget "Últimas 10 órdenes" colapsable con tabla.
   - Dejar pestaña abierta 30+ segundos → ver refetch automático en Network tab (`GET /dashboard/snapshot` + `GET /orders?page=0&size=10` cada 30s).
   - Click botón ↻ en EquityCard → re-fetch inmediato.
   - `docker exec bloomtrade-redis redis-cli KEYS "market-data:price:*"` → debería mostrar 25 keys (uno por ticker).
   - Operar en `/trade` con side=SELL → `TickerDropdown` debería mostrar SOLO los tickers en posición (deuda #15 cerrada). Si no hay posiciones → dropdown disabled + banner amber.
   - (Opcional) Simular Alpaca caído (DNS bloqueado a `data.alpaca.markets`): banner amarillo + tabla con "—" + equity sin marketValue.

2. **Commit + Push + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f18-f17.txt` (ruta completa literal — P6).
   - `git push -u origin feat/HU-F18-F17-dashboard-historial`
   - `gh pr create` o desde GitHub. Squash and merge a `main`.

**Cómo arrancar Día 10 (estabilización + JMeter + docs finales) tras merge HU-F18+F17:**

1. **Pre-requisito**: HU-F18+F17 mergeada en `main`. **Sprint 2 funcional cerrado**: 9 HUs del MVP §2.1 + bonus F17. No quedan HUs funcionales para implementar.
2. **JMeter** (deuda #7 ROADMAP §6 — Sprint 2 Review pendiente): ejecutar planes de prueba para ESC-R1 (1500 órdenes simultáneas) y ESC-R2 (1500 dashboards). Crear `load-tests/` con .jmx. Documentar resultados en `docs/load-tests/results.md`. Pre-trabajo: 1-2 horas con tutoriales (riesgo #7 ROADMAP — JMeter desconocido).
3. **Diagramas C4 + secuencia de orden + despliegue**: actualizar `docs/` con el estado real del código (no la implementación inicial). Diagrama de secuencia "envío y ejecución de orden" exigido por PDF del curso.
4. **Sprint 1+2 Review/Retro diferidos**: `docs/sprints/sprint-1-review.md`, `sprint-1-retro.md`, `sprint-2-review.md`, `sprint-2-retro.md`.
5. **Informe Final**: secciones del PDF del curso. Demo del MVP completo grabada.
6. **Deudas remanentes** que pueden cerrarse rápido si tiempo: (1) mini-HU `HU-F0X-token-rotation-logout` agrega `AuthenticationEntryPoint` (~30 min). (2) D28 F09 hardening en `IntegrationConfig.validateCredentials`. (3) Variables comentadas en `.env.example`.
7. **Stretch goals (si Día 10 va adelantado)**: HU-F15 cancelar orden (#1 en orden de promoción §3.4 — única que queda sin promover), `D-METRICS-CACHE-HIT-RATIO` (micrometer custom).

---

## Cómo continuar (post HU-F16+F21 → HU-F18 Día 9) [HISTÓRICO — completado en sesión 2026-05-25]

**Estado actual (2026-05-24, cierre de sesión implementación HU-F16+F21):**

- HU-F16+F21 implementación completa en branch `feat/HU-F16-F21-portafolio-saldo`. **286 tests verdes (231 unit + 55 IT)**, 0 regresiones. Frontend `npm run build` verde (2567 módulos).
- HITOs 1–5 ✅ (backend + tests IT + frontend build + verify completo). HITO 4 smoke visual humano pendiente (no bloqueante — sin tests UI por [[feedback-coverage-vs-velocidad]]). HITO 6 ⏸️ commit + push + PR firmado por humano.
- Decisiones emergentes registradas en `plan.md` §2.4: D17 (403 vs 401 sin JWT — cross-cutting, diferido a mini-HU token-rotation-logout), D18 (`OrderByTicker` en repo — JsonPath filter no funciona en MockMvc + UX bonus orden estable).
- SPEC sin bump v1.1 — todas las decisiones emergentes son implementation-detail, no afectan contratos API.
- APRENDIZAJES.md con sección Día 8 (9 reflexiones técnicas + meta sobre SDD en bundle small).

**Lotes HU-F16+F21 cerrados (A–F):**

| Lote | HITO | Resumen | Tests añadidos |
|---|---|---|---|
| A | 1 ✅ | 4 DTOs records (`BalanceResponse`, `PendingOrderDto`, `PositionDto`, `PortfolioPositionsResponse`). `OrderRepository.findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc` derived query. `PositionRepository.findByUserIdAndQuantityGreaterThan` (D12 defensa qty=0). `PortfolioService` +inject OrderRepository, +`getPendingOrders`, +`getBalanceEntity`, modificación `getPositions` con filtro qty>0. | 7 unit (1 filter qty=0 + 4 pendingOrders + 2 getBalanceEntity) |
| B | 2 ✅ | `PortfolioConfig` con `@Bean ExecutorService marketDataExecutor` (8 threads daemon, destroyMethod). `MarketDataOrchestrator` con `CompletableFuture.supplyAsync(...).completeOnTimeout(1500ms)` por ticker — cap garantiza endpoint bounded ~2s independiente de retries internos del adapter. | 7 unit (empty/null/all-success/one-exception/one-timeout/all-fail/partial-mix) |
| C | 3 ✅ | `PortfolioMapper` manual (stringificado scale=2 HALF_UP, cálculos compuestos marketValue/PnL/PnLPct, marketDataAvailable lógica 4-casos). `PortfolioController` 2 endpoints REST `@AuthenticationPrincipal AuthenticatedUser`. **2 bugs emergentes durante el lote**: D17 (403 vs 401 cross-cutting), D18 (ORDER BY ticker para JsonPath determinístico). **Fix adicional**: 1ra versión usó `@AuthenticationPrincipal User user` (entity JPA) → 500 InternalServerError. Convención del proyecto es `AuthenticatedUser principal` + `principal.userId()` (grep al codebase lo confirmó). | 10 PortfolioMapperTest + 8 PortfolioControllerIT |
| D | 4 ✅ | Frontend: `types/api.ts` +4 interfaces. `portfolioApi.ts` 2 wrappers. 2 hooks React Query (`useBalance`, `usePortfolioPositions`) con `staleTime: 30s + refetchOnWindowFocus`. 4 components (`BalanceCard` con `RefreshCw` lucide-react + relative time date-fns, `PositionsTable` con P&L color-coded `text-emerald-600`/`text-rose-600` + iconos `TrendingUp/TrendingDown` para a11y, `PendingOrdersPanel` colapsable `<details open>`, `MarketDataBanner` 3 estados). `PortfolioPage` en `/pages/`. `App.tsx` ruta `/portfolio` protegida. `AppHeader` link "Portafolio". `messages.es.ts` +objeto `portfolioMessages` con 10 copys ES-CO. `npm run build` verde 2567 módulos. Smoke visual humano pendiente. | 0 (skipped per [[feedback-coverage-vs-velocidad]]) |
| E | 5 ✅ | `PortfolioControllerIT` +4 IT (cross-user `/positions` con WireMock verify NO leak, cross-user `/balance`, `lastUpdatedAt` cambia tras `credit` real, defensa qty=0 desde HTTP). Helper `SeededUser` record + `seedSecondUser`. `mvn verify` completo: **286 tests verdes (231 unit + 55 IT)**, 0 regresiones. | 4 IT |
| F | 6 ⏸️ | `plan.md` §2.4 con D17–D18. `APRENDIZAJES.md` sección "Día 8 — HU-F16+F21". `AGENTS.md` handoff actualizado (este bloque). Commit message en `C:\Users\juang\AppData\Local\Temp\bt-hu-f16-f21.txt`. SPEC NO bump (decisiones implementation-detail). | — |

**Bugs encontrados y arreglados durante implementación HU-F16+F21:**

1. **D17 — 403 vs 401 sin JWT** atrapado por `PortfolioControllerIT.get*_withoutJwt_returns401`. Causa: el filter solo emite 401 con token inválido/expirado; sin header Spring Security 6 cae en 403 default. Fix scope F16: ajustar tests a esperar 403 + comentario explicativo. Fix real diferido a mini-HU `HU-F0X-token-rotation-logout` que ya va a tocar el filter (agregar `AuthenticationEntryPoint`). NO arreglar acá (cross-cutting, riesgo regresión).
2. **D18 — JsonPath filter no funciona en MockMvc** atrapado por `getPositions_happyMarkToMarket` (jsonPath `$.positions[?(@.ticker=='AAPL')]` retornaba null). Fix: rename `findByUserIdAndQuantityGreaterThan` a `OrderByTicker` (alfabético ASC) + tests usan índices `positions[0]/[1]`. UX bonus: listado estable entre requests.
3. **Fix de convención `@AuthenticationPrincipal AuthenticatedUser` vs `User`** (no registrado como D — convención obvia tras grep). Causa: 500 InternalServerError porque Spring inyecta record `AuthenticatedUser`, no entity JPA. Aprendizaje: al crear primer controller en módulo nuevo, `grep -r "@AuthenticationPrincipal"` antes de elegir el tipo.

**Lo primero del humano (HITO 6 pre-merge):**

1. **Smoke visual** (no bloqueante — usuario lo hará "después"):
   - `docker compose up -d --build` (rebuild para que frontend tome los nuevos archivos).
   - Login → /portfolio. Esperado: card de saldo arriba con USD formato es-CO + tabla posiciones (de F09/F10 si existen) con P&L color-coded + sección "Órdenes en cola" si las hay (BUY o SELL queued del demo).
   - Operar en /trade (BUY o SELL pequeño) y volver → datos refrescados automáticamente (refetchOnWindowFocus).
   - Click botón ↻ del BalanceCard → ambas queries re-fetcheadas.
   - (Opcional) Simular Alpaca data API caído — `docker compose pause` no aplica al adapter (es outbound HTTP a `data.alpaca.markets`); alternativa: bloquear DNS con `127.0.0.1 data.alpaca.markets` en hosts. Banner amarillo "Precios de mercado temporalmente no disponibles" + tabla con "—".

2. **Commit + Push + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f16-f21.txt` (ruta completa literal — P6).
   - `git push -u origin feat/HU-F16-F21-portafolio-saldo`
   - `gh pr create` o desde GitHub. Squash and merge a `main`.

**Cómo arrancar HU-F18 Día 9 tras merge HU-F16+F21:**

1. Pre-requisito: HU-F16+F21 mergeada en `main` (los hooks `useBalance` + `usePortfolioPositions` + el `MarketDataOrchestrator` ya existen — F18 los va a reusar masivamente).
2. SDD Paso 1: crear `specs/HU-F18-dashboard/SPEC.md`. **No bundle**: F18 es solo 1 HU y abarca su propia complejidad (charts, widgets, posiblemente WebSocket si se decide live updates). Cuestionario antes de SPEC (3-4 preguntas críticas): (a) widgets a incluir (saldo + P&L total + top 3 ganadores + top 3 perdedores + curva equity?), (b) refresh strategy (manual + on-focus como F16, o polling intervalado, o WebSocket), (c) librería de charts (recharts vs chart.js vs visx — verificar STACK.md), (d) responsive mobile (tabs o stack?).
3. **Reuso máximo de F16+F21**: el `MarketDataOrchestrator` ya está cableado, fan-out paralelo con cap 1.5s. F18 puede pedir precios de tickers de interés del usuario (no solo los que tiene en posición) reusando exactamente el mismo patrón. Si se quiere cache (deuda #19 nueva), agregar Redis ahí.
4. **HU-F17 historial de operaciones** podría incluirse como bundle con F18 si comparten página (`/dashboard` con widget de "últimas órdenes"). Discutir con humano al arrancar Día 9.
5. **Deuda emergente para limpiar en F18**: borrar `InvalidSideException.sideNotYetImplemented()` dead code (#16 deuda viva), extender `useTickerOptions` para filter por posiciones cuando side=SELL (#15).

**Estado anterior HU-F10 (cerrado y mergeado 2026-05-24 PR #7):**

- 6 bundles mergeados en `main`: HU-F01 (PR #2), HU-F02+F03 (PR #3), HU-F04+F20 (PR #4), HU-F06 (PR #5), HU-F09 (PR #6), **HU-F10 (PR #7, merge commit `e5a8943`)**.
- HU-F10 cerró con 250 tests verdes; ahora HU-F16+F21 sumó +36 → 286 totales.
- Las 21 decisiones D1–D21 de F10 están documentadas en `specs/HU-F10-orden-venta-market/plan.md` para referencia futura.

---

## Cómo continuar (post HU-F10 → HU-F16+F21 Día 8) [HISTÓRICO — completado]

**Estado al cierre HU-F10 (2026-05-24):**

- HU-F10 implementación completa en branch `feat/HU-F10-orden-venta-market`. **250 tests verdes (207 unit + 43 IT)**, 0 regresiones. Frontend `npm run build` verde.
- HITOs 1–4 ✅ (backend + tests IT). HITO 5 ⏸️ smoke E2E humano (requiere mercado abierto). HITO 6 ⏸️ commit + push + PR firmado por humano.
- Decisiones emergentes registradas en `plan.md` §2.4: D17 (lock canónico balances→positions), D18 (`noRollbackFor` en `validateSellable` + `decrementPosition`), D19 (skip email SELL provisional Lote B→C), D20 (side en TODOS los audit events), D21 (dispatch en listener no en Notifier).
- SPEC sin bump v1.1 — todas las decisiones emergentes son implementation-detail, no afectan contratos API.
- APRENDIZAJES.md con sección Día 7 (8 reflexiones técnicas + meta sobre SDD).

**Lotes HU-F10 cerrados (A–F):**

| Lote | HITO | Resumen | Tests añadidos |
|---|---|---|---|
| A | 1 ✅ | Verificación V5 (D13 confirmó BD apta sin V6). `PositionRepository` + lock pessimistic + `deleteByUserIdAndTicker`. `Position.decrementBy + isDepleted`. `UserBalance.applyCredit`. `PortfolioService.credit + decrementPosition + findPosition`. 2 excepciones nuevas en `trading/exception/` (`ShortSellingNotAllowedException`, `InsufficientSharesException`). | 12 unit (credit happy/precision/edge + decrement residual/delete/insufficient/short/qty0-defensivo + findPosition × 2) |
| B | 2 ✅ | `Order.markAsExecuted` side-aware (BUY suma, SELL resta — D4 honra quotedCommission, no recalcula). `QuoteResponse` + `sufficientShares` + `userShares` con @Schema. `OrderResponse` @Schema side-aware. 4 records de evento + `side` (D20 emergente). `PortfolioService.validateSellable`. `TradingService` refactor con dispatch interno por side (`handleBuyTx` extraído + `handleSellTx` nuevo). `GlobalExceptionHandler` + 2 handlers 409. `validation-messages.properties` +2 códigos. | 8 unit nuevos (3 quote SELL + 5 placeOrder SELL) + 3 unit Order side-aware + 5 OrderEventListener actualizados |
| C | 3 ✅ | `Notifier` rename 4 métodos a `*Buy` + 4 nuevos `*Sell` (8 total). `MailNotifier` reescrito. 4 templates Thymeleaf `-sell.html` (templates `-buy.html` ya existían desde F09). 2 DTOs extendidos (`OrderExecutedEmailCommand` +positionResultingQty/Deleted, `OrderQueuedEmailCommand` +positionResultingQty). `OrderEventListener` dispatch real por `event.side()`. Skip email amplió a `SHORT_SELLING_NOT_ALLOWED` + `INSUFFICIENT_SHARES`. | 5 OrderEventListener nuevos SELL dispatch + 2 anti-spam shortSelling/insufficientShares |
| D | 4 ✅ | `TradingControllerSellIT` (9 tests E2E con WireMock — quote/placeOrder happy + total liquidation + short selling + insufficient shares + Alpaca rejected + Alpaca down + idempotency). `TradingServiceSellConcurrencyIT` (3 tests — idempotency × 10 + overlap position × 2 + BUY+SELL concurrente sin deadlock). **2 fixes emergentes durante el lote**: D17 lock canónico (deadlock atrapado), D18 noRollbackFor en validateSellable (500→409). | 12 IT nuevos |
| E | 5 ⏸️ | Frontend: `types/api.ts` + 2 campos QuoteResponse. `messages.es.ts` +2 códigos. `OrderForm` habilitado SELL toggle + submit label side-aware + hint dropdown. `OrderQuotePanel` branching side-aware (totalLabel, projectedBalance suma/resta, posición restante/liquidación, blockedReason 3 casos, confirmLabel). `OrderConfirmationToast` headline/mensaje side-aware (Vendiste/Compraste, queued SELL "recibirás crédito al abrir"). `npm run build` verde 195 módulos. Smoke E2E humano pendiente. | 0 (frontend skipped per [[feedback-coverage-vs-velocidad]] — validación humana HITO 5) |
| F | 6 ⏸️ | `plan.md` §2.4 con D17–D21 + changelog v1.1. `APRENDIZAJES.md` sección "Día 7 — HU-F10". `AGENTS.md` handoff actualizado (este bloque). Commit message en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt`. SPEC NO bump (decisiones implementation-detail). | — |

**Bugs encontrados y arreglados durante implementación HU-F10:**

1. **D17 — Deadlock BUY+SELL concurrente** atrapado por test `TradingServiceSellConcurrencyIT#concurrency_buyAndSellSameTicker_*`. Fix: `validateSellable` toma lock `app.user_balances` PRIMERO (orden canónico balances→positions consistente con BUY que toma balances en debit antes de positions en upsert). Sin esto, Postgres reportaba "deadlock detected" en `SELECT FOR UPDATE` de balances. Documentado javadoc PortfolioService.
2. **D18 — `UnexpectedRollbackException` en SHORT_SELLING/INSUFFICIENT_SHARES** atrapado por `TradingControllerSellIT` (7/9 pasaban, 2 daban 500). Fix: agregar `noRollbackFor={ShortSellingNotAllowedException, InsufficientSharesException}` a `validateSellable` y `decrementPosition`. Patrón idéntico a D24/D27 F09.

**Lo primero del humano (HITO 6 pre-merge):**

1. **Smoke E2E manual HITO 5** (idealmente con NYSE abierto):
   - `docker compose up -d --build` → login → /trade → toggle SELL.
   - **Pre-requisito**: tener al menos 1 posición real en `app.positions` (no `PENDING+alpacaOrderId`). Si la del demo F09 quedó encolada, ejecutar BUY × 5 AAPL primero con mercado abierto.
   - SELL AAPL × N (N ≤ posición) → quote panel muestra "Producto neto a recibir" + "Posición restante: K AAPL" + "Saldo después" incrementado → confirmar → toast emerald "Vendiste 5 AAPL — recibiste USD X".
   - Verificar email en MailHog `localhost:8025`: asunto "Tu orden de venta fue ejecutada" + cuerpo con producto neto + condicional "Posición restante" o "Has liquidado completamente".
   - Verificar Kibana `localhost:5601`: evento `ORDER_EXECUTED` con `details.side="SELL"`, `details.positionResultingQty=N`, `details.positionDeleted=true|false`.
   - `psql -c "SELECT * FROM app.orders WHERE side='SELL' ORDER BY submitted_at DESC LIMIT 1"` muestra `status=EXECUTED` + `execution_total ≈ subtotal − commission`.
   - `psql -c "SELECT * FROM app.positions WHERE user_id = '...'"` decrementada o borrada.
   - `psql -c "SELECT balance FROM app.user_balances WHERE user_id = '...'"` incrementado.
   - Alpaca paper dashboard (`https://app.alpaca.markets/paper/dashboard/overview`) muestra la venta.
   - **Smokes negativos**: SELL ticker sin posición → toast rojo "No tienes posición en {ticker}". SELL > posición → "Solo tienes {N} disponibles para vender".

2. **Commit + Push + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` (ruta completa literal — P6).
   - `git push -u origin feat/HU-F10-orden-venta-market`
   - `gh pr create` o desde GitHub. Squash and merge a `main`.

**Cómo arrancar HU-F16+F21 Día 8 tras merge HU-F10:**

1. Pre-requisito: HU-F10 mergeada en `main` (los `PortfolioService.getBalance/getPositions` ya existen del F09+F10 — F16+F21 son CRUDS pequeños encima).
2. SDD Paso 1: crear `specs/HU-F16-consultar-portafolio/SPEC.md` + `specs/HU-F21-consultar-saldo/SPEC.md`. **Decisión metodológica del usuario** (2026-05-23): NO batch-spec — cada HU su propio SPEC. Pero F16+F21 son tan small (~30% efforts F09 cada una) que podrían compartir un SPEC bundle `HU-F16-F21-portafolio-saldo/SPEC.md`. Discutir con usuario al arrancar Día 8.
3. Endpoints estimados: `GET /api/v1/portfolio/positions` (lista) + `GET /api/v1/portfolio/balance` (con currency). Frontend: nuevo tab `/portfolio` en AppHeader, hooks `usePortfolioPositions + useBalance`.
4. **Reuso máximo**: PortfolioService ya tiene `getBalance(userId)` y `getPositions(userId)` readOnly. Solo agregar 2 endpoints + 2 DTOs + 2 hooks frontend + 1 página. Sin migración nueva, sin Alpaca, sin event listeners.
5. Incluir órdenes `PENDING + alpacaOrderId` en `GET /positions` como sección separada "órdenes en cola" (D29 F09 + D9 F10) — mitigación de la deuda viva #8.

**Estado bundle anterior HU-F09 (cerrado y mergeado 2026-05-22 PR #6):**
- 5 bundles mergeados en `main`: HU-F01 (PR #2), HU-F02+F03 (PR #3), HU-F04+F20 (PR #4), HU-F06 (PR #5), **HU-F09 (PR #6, merge commit `1bab23b`)**.
- Working tree con 3 archivos **untracked** (NO commitear todavía — se commitean junto con la implementación de F10):
  - `specs/HU-F10-orden-venta-market/SPEC.md` v1.0 (~580 líneas, aprobado)
  - `specs/HU-F10-orden-venta-market/plan.md` v1.0 (~280 líneas, aprobado)
  - `specs/HU-F10-orden-venta-market/tasks.md` (~480 líneas, aprobado)
- Sin ramas activas. `git status` muestra los 3 untracked en `specs/`.
- `mvn verify` última corrida verde (sesión F09): 219 tests (188 unit + 31 IT).
- `npm run build` última corrida verde: 195 módulos.
- Stack Docker funcional (último uso F09 HITO 8): postgres + redis + elasticsearch + logstash + kibana + mailhog + backend + frontend.
- Decisión metodológica explícita del usuario (esta sesión): generar SDD docs solo de HU-F10 (no batch de las 3 HUs restantes) porque batching es mala práctica — el SPEC F16+F21 y F18 dependen de contratos reales que F10 va a establecer al implementarse. HU-F16+F21 y HU-F18 quedan con SDD Paso 1 pendiente, a hacer DESPUÉS de cerrar F10.

**Lo primero en la sesión de implementación HU-F10 (Día 7):**

1. **Leer este `AGENTS.md` completo + `CLAUDE.md` completo**.
2. **Leer los 3 docs SDD aprobados de F10 (en orden)**:
   - `specs/HU-F10-orden-venta-market/SPEC.md` — qué construir + criterios de aceptación.
   - `specs/HU-F10-orden-venta-market/plan.md` — 16 decisiones técnicas D1–D16, 6 lotes A–F, riesgos.
   - `specs/HU-F10-orden-venta-market/tasks.md` — descomposición granular T1.1–T6.11 (~80 tareas).
3. **Leer F09 como referencia obligatoria** (no para SDD nuevo, sino para entender el andamio que F10 extiende): `specs/HU-F09-orden-compra-market/SPEC.md` v1.1 + `plan.md` (D1–D29 emergentes).
4. **Crear branch + arrancar Lote A**:
   ```
   git checkout main
   git pull
   git checkout -b feat/HU-F10-orden-venta-market
   ```
   Después arrancar T1.1–T1.4 (verificación V5 — D13 del plan). Si V5 no cubre F10, crear V6 correctiva ANTES de continuar.
5. **NO crear SPEC/plan/tasks nuevos** — los 3 están listos y aprobados. Cualquier decisión emergente durante implementación va a `plan.md` §2.4 nueva sección "Decisiones emergentes durante implementación (D17–Dxx)" siguiendo patrón F09 D23–D29.
6. **Cadencia de lotes** [[feedback-cadencia-sdd]]: validar al cierre de cada HITO (1 a 6 del tasks.md), NO micro-checkpoint tras cada T1.x. Lotes A–D son backend; E es frontend; F es cierre (APRENDIZAJES + AGENTS.md handoff + commit message).
7. **HITO 5 demo manual** requiere NYSE abierto (martes 26-May 2026 8:30 AM hora COL en adelante, post-Memorial Day USA). Si la sesión arranca con mercado cerrado: completar HITOs 1–4 (backend + unit/IT) y posponer HITO 5 a la primera ventana abierta. Pre-HITO 5 setup: tener al menos 1 posición real en `app.positions` (no `PENDING+alpacaOrderId`) del usuario testing. Si la del demo F09 quedó encolada, ejecutar BUY × 5 AAPL primero.
8. **Cierre HITO 6**: commit + push + PR. Mensaje preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` (P6 ruta completa). El commit incluye los 3 docs SDD (untracked actualmente) + todo el código de implementación + APRENDIZAJES.md + handoff actualizado.

**Pre-requisitos para arrancar implementación HU-F10:**

- `.env` ya tiene creds Alpaca pobladas (HU-F09). NO se agregan vars nuevas.
- Docker stack puede levantarse con `docker compose up -d` (sin `--build` si no hay cambios en Dockerfile — pero F10 no toca Dockerfile).
- `ALPACA_BASE_URL=https://paper-api.alpaca.markets` (sin `/v2` — D28 F09).
- V5 ya migrada con `chk_order_side CHECK (side IN ('BUY', 'SELL'))` y `chk_position_quantity CHECK (quantity >= 0)` — T1.2/T1.3 lo verifican.
- Reconciliación de Alpaca paper account: en https://app.alpaca.markets/paper/dashboard/overview verificar estado. Si la orden encolada del viernes filee, hay posición real en Alpaca que BloomTrade aún tiene como PENDING — drift conocido (deuda #8/#13 de este AGENTS.md), no bloquea F10.

**Lotes HU-F09 cerrados (A–G):**

| Lote | HITO | Resumen | Tests añadidos |
|---|---|---|---|
| A | 1 ✅ | env vars + STACK.md §7.2 (Polygon→Alpaca) + V5 (orders/positions/commission_rates) + 6 entidades + 4 repositories + `application.yml` blocks `alpaca:` + `trading:` + retry instances | (compile + Flyway) |
| B | 2 ✅ | `IntegrationConfig` con 2 `RestClient` (trading + data) + `AlpacaTradingAdapter.submitMarketOrder/getOrder` + `MarketDataAdapter.getLatestPrice` + 3 excepciones + 3 DTOs + `SubmitMarketOrderCommand` | 12 unit (MockRestServiceServer) |
| C | 3 ✅ | `ConfigurationManager` + `CommissionManager` (BigDecimal HALF_UP scale=2) + `MarketScheduleManager` (stub MVP) | 17 unit (@CsvSource params) |
| D | 4 ✅ | `PortfolioService.debit/upsertPosition/getBalance/getPositions` + `UserBalance.applyDebit` + `InsufficientFundsException` + lock pessimistic | 8 IT (incl. concurrency 2-thread) |
| E | 5 ✅ | 4 DTOs API + `OrderMapper` (manual, BigDecimal→String) + 2 excepciones nuevas + 6 handlers en `GlobalExceptionHandler` + 7 códigos i18n + `TradingService.quote/placeOrderTx` + `OrderController` con Swagger | 12 unit |
| F | 6 ✅ | 3 EmailCommand DTOs + `Notifier` +3 métodos + `MailNotifier` +3 impl + 3 templates Thymeleaf + `AuditEventType` +10 entries + `OrderEventListener` con `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW, readOnly)` | 5 IT |
| G | 7 ✅ | `TradingControllerIT` (9 tests E2E con WireMock) + `TradingServiceConcurrencyIT` (idempotency × 10 + concurrency × 2) + 5 fixes críticos (D23–D27 emergentes en `plan.md` §2.4) | 11 IT |

**Bugs encontrados y arreglados durante Lote G HU-F09** (registrados como D23–D27 en plan.md §2.4 de F09):
1. Pre-validación fondos faltante antes de INSERT → agregada (D23).
2. `noRollbackFor` necesario en `placeOrderTx` + `PortfolioService.debit` para preservar filas FAILED/REJECTED (D24, D27 emergente del nested tx rollback-only).
3. Race condition find→INSERT con `clientOrderId` idempotency → `ConcurrentHashMap` lock + self-injection lazy para commit-DENTRO-de-lock (D25).
4. Hibernate L1 cache rompía `SELECT FOR UPDATE` → projection-only `findBalanceProjectionByUserId` (D26).
5. Retries override en `application-test.yml` (50ms × 2 vs 1s × 3) — solo en perfil test.

**Estado bundle anterior HU-F06 (cerrado y mergeado 2026-05-21):**

| Lote | Resumen | HITO |
|---|---|---|
| A — Setup + entidades | `pom.xml` + `stripe-java 28.0.0`. `STACK.md` §2.3 actualizado. `.env.example` y `docker-compose.yml` con `STRIPE_API_KEY` (RAK), `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_MONTHLY/YEARLY`, `APP_BASE_URL`. V4 migration: `users.stripe_customer_id`, `app.subscriptions` (con `uq_one_active_subscription_per_user` partial unique), `app.stripe_webhook_events` (`stripe_event_id UNIQUE`). User entity + `linkStripeCustomer(...)` D8. Enums `SubscriptionStatus`, `BillingPlan`, `WebhookEventStatus`. Entidades `Subscription` (con métodos de dominio `scheduleCancellation/reactivate/markAsCancelled/markAsPastDue/syncPeriod`) y `StripeWebhookEvent`. 2 repositories. `StripeConfig` que valida key + WARN si no es `rk_`. SecurityConfig exime `/api/v1/webhooks/stripe`. | HITO 1 ✅ (compile + V4 DDL syntactically valid en BD) |
| B — StripeAdapter | `StripeAdapter` (5 métodos: `createCustomer`, `createCheckoutSession`, `createBillingPortalSession`, `retrieveSubscription`, `constructWebhookEvent`). `@Retry(name="stripeApi")` configurado en `application.yml` (3×backoff 1s/3s/9s). `Idempotency-Key` UUID por request en mutables (D4). **NO incluye `payment_method_types`** (D3 trap). `StripeApiException` con `stripeErrorCode`. | Compile verde |
| C — Service + Controller + isPremium en /me | 5 DTOs (CheckoutSessionRequest/Response, PortalSessionResponse, SubscriptionDto, SubscriptionStatusResponse). `SubscriptionMapper` MapStruct sin `stripeCustomerId`/`stripeSubscriptionId` (D21). 3 excepciones + 4 handlers en GlobalExceptionHandler. ValidationMessages +5 códigos. `SubscriptionService` con D8 split (`ensureStripeCustomer` REQUIRES_NEW). `SubscriptionController` 3 endpoints `@AuthenticationPrincipal`. **G5**: extendido `UserProfileResponse` con `boolean isPremium`; ajustada firma `UserProfileMapper.toResponse(User, boolean)`; `ProfileService` inyecta `SubscriptionService.isPremium`. Tests previos (HU-F04) ajustados. AuditEventType +3. | HITO 2 ⏸️ (curl /checkout-session requiere setup Stripe del usuario) |
| D — Webhook handler + idempotencia | `StripeWebhookHandler` con: signature verify (lanza `WebhookSignatureInvalidException`), idempotencia vía INSERT con catch de `DataIntegrityViolationException`, switch sobre 4 event types (checkout.completed, sub.updated con detect de transición cancel/reactivate, sub.deleted, invoice.payment_failed). Audit con 10 event types nuevos. `StripeWebhookController` con `@RequestBody String rawBody` para preservar bytes. | HITO 3 ⏸️ (requiere `stripe-cli trigger`) |
| E — Notification + 4 templates | `Notifier` interface +4 métodos. `MailNotifier` implementa con `@Async`. 4 templates Thymeleaf inline-CSS: `welcome-premium.html`, `subscription-scheduled-to-cancel.html`, `subscription-expired.html`, `subscription-payment-failed.html`. AuditEventType +4 `*_EMAIL_FAILED`. | Verificable en MailHog tras HITO 3 |
| F — Tests backend críticos | `SubscriptionMapperTest` (2 tests: mapeo OK, no-leak de stripe IDs en JSON). `SubscriptionServiceTest` (9 tests: happy, customer reuso, 409 already_active, 409 no_stripe_customer, Stripe API error con audit FAILED, portal happy, isPremium true/false, status null sub). `StripeWebhookHandlerTest` (3 tests: signature inválida, duplicate short-circuit, tipo desconocido ignorado). **Skip por velocidad**: IT con WireMock (deuda registrada), tests específicos de los 4 handlers individuales (cobertura E2E manual con stripe-cli). | HITO 4 ✅ (124 tests, BUILD SUCCESS) |
| G — Frontend | `types/api.ts` +6 types. `subscriptionApi.ts` 3 wrappers. 3 hooks (`useSubscription` con polling opcional, `useStartCheckout` redirige a Stripe, `useOpenBillingPortal` redirige a Portal). `messages.es.ts` +4 códigos. `PremiumPage` 4 estados (A: sin sub, B: ACTIVE sin cancel, C: ACTIVE con cancel, D: terminal). `PremiumSuccessPage` con polling y redirect a /dashboard en 3s. `PremiumCancelPage`. App.tsx +3 rutas. AppHeader + link "Premium". | HITO 5 ⏸️ (E2E con stripe-cli + Stripe Dashboard) |
| H — Cierre | APRENDIZAJES.md sección "Día 4 — HU-F06" con 9 reflexiones técnicas. AGENTS.md handoff (este bloque). Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f06.txt`. | HITO 6 pendiente del commit + push + PR del humano |

**Pendiente del humano antes de mergear:**

1. **Setup Stripe (~10 min)**:
   - Crear Test Mode RAK en https://dashboard.stripe.com/test/apikeys con permisos: Customers (write), Checkout Sessions (write), Subscriptions (read), Billing Portal Sessions (write).
   - Crear 2 Products + Prices en https://dashboard.stripe.com/test/prices: USD $12/mo (`STRIPE_PRICE_MONTHLY`) y USD $120/yr (`STRIPE_PRICE_YEARLY`). Copiar los `price_...` IDs.
   - Activar Customer Portal: Settings → Billing → Customer Portal → habilitar cancel, payment method update, invoice history.
   - Instalar `stripe-cli`: `scoop install stripe-cli` en PowerShell.
   - `stripe login` (autenticación browser).
   - `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe` en otra terminal → copiar el `whsec_...` que sale al output.
   - Poblar `.env` con los 4 valores Stripe.

2. **HITOS visuales** (~15 min con setup arriba):
   - `docker compose up -d --build backend frontend` (rebuild para que tomen las env vars nuevas + V4 migrate).
   - Verificar logs backend: `Stripe SDK inicializado con key rk_test_****`. Si dice `sk_test_****` → WARN (cambiar a RAK).
   - Browser: login + MFA → menú "Premium" → activar mensual → 4242 4242 4242 4242 → /premium/success → ver banner premium → "Gestionar suscripción" → cancelar en portal → volver a /premium → banner amarillo.
   - Verificar email en MailHog (`localhost:8025`): "Bienvenido a Premium" + "Tu suscripción se cancelará el X".
   - Verificar `psql ... -c "SELECT * FROM app.subscriptions;"` y `SELECT * FROM app.stripe_webhook_events;`.

3. **Commit + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f06.txt`
   - `git push -u origin feat/HU-F06-suscripcion-premium`
   - `gh pr create ...` o desde GitHub.

**Decisiones registradas (D1–D21 en plan.md):**

- D1: stripe-java 28.0.0, sin override de apiVersion (skill: "Always use the latest").
- D2: RAK (rk_) en lugar de sk_. `STRIPE_API_KEY` env var genérica.
- D3: NUNCA `payment_method_types` en checkout (DPM trap de la skill).
- D4: Idempotency-Key UUID por request en mutables.
- D5: Webhook raw body + signature verify, exento de JWT.
- D6: Idempotencia inbound vía UNIQUE constraint + catch DataIntegrityViolationException.
- D7: Customer Portal endpoint reemplaza /cancel (v1.2 SPEC).
- D8: Split de transacciones — `ensureStripeCustomer` en REQUIRES_NEW para evitar huérfanos.
- D9: Sub-paquete `auth/subscription/` (no módulo nuevo).
- D10: `invoice.payment_failed` → downgrade inmediato a PAST_DUE (MVP — sin grace period).
- D11–D13: Notifier extendido + 4 templates Thymeleaf.
- D14: AuditEventType +13 entries.
- D17: PremiumPage con 4 estados inline (no proliferación de componentes).
- D18: stripe-cli requerido en dev local.
- D19: tests IT con WireMock (deuda registrada — saltado por velocidad).
- D21: stripe_customer_id / stripe_subscription_id NUNCA en responses API. Verificado con test.
- **D22** (runtime fix HU-F06 Lote D): `EventDataObjectDeserializer.getObject()` retorna `Optional.empty()` cuando la API version del evento de Stripe difiere de la del SDK (caso real: evento `2026-04-22.dahlia` vs `stripe-java 28.0.0`). Solución: `extractObject` usa `deserializeUnsafe()` como fallback. Patrón estándar documentado en stripe-java README — riesgo aceptable porque solo accedemos a campos estables (subscription id, customer id, period start/end, metadata).
- **D23** (runtime fix cross-HU 2026-05-21): el access token + user se persisten en `localStorage` (`bloomtrade.accessToken`, `bloomtrade.user`) en lugar de vivir solo en memoria. Revierte parcialmente D12 HU-F02 §12.3 por UX inaceptable: cualquier F5 / vuelta de checkout Stripe / pestaña nueva forzaba re-login. Trade-off de seguridad XSS aceptado para MVP; mitigado por (a) TTL 15 min sin refresh silencioso, (b) `onUnauthorized` limpia ambas keys al primer 401. La mini-HU `HU-F0X-token-rotation-logout` reemplazará esto con cookie HttpOnly + refresh tokens y eliminará el localStorage.

**Deuda registrada (no bloqueante para MVP):**

- Tests IT con WireMock para los 4 webhook handlers individuales — actualmente solo signature + idempotency + tipo desconocido tienen tests.
- Test assertion explícita de no `payment_method_types` en code (D3) — saltado por velocidad.
- Reconciliation job nocturno entre Stripe y BD — para producción real.
- Tests frontend del Lote G — saltados (mismo motivo HU-F04).
- Migración del frontend a `createBrowserRouter` (DataRouter) → habilita `useBlocker` (deuda HU-F04 D22 — sigue válida).
- `ARCHITECTURE.md` §5 todavía lista interfaces con prefijo `I` (deuda doc-only HU-F02).

**Historial de bundles previos cerrados** (HU-F04+F20 — Día 3): SPEC v1.1 + 7 lotes + HITOs 1-5, 28 tests nuevos. Decisiones D19-D22 (encapsulación PATCH, 403 vs 401 deuda, audit post-commit, `useBlocker` deferido). Mergeado en PR #4 (commit `0caeed7`).

---

## Estilo de trabajo del usuario (preferencias validadas)

Estas reglas vienen de feedback explícito del usuario en sesiones previas; cualquier agente
debe respetarlas para no chocar con el estilo establecido.

| # | Preferencia | Cómo aplicarla |
|---|---|---|
| P1 | **Velocidad sobre cobertura.** SonarCloud a ~60% es aceptable por el plazo del MVP. | NO sugerir tests reflexivamente cuando el quality gate flagee. Documentar el gap como una decisión `Dxx` en `plan.md` de la HU. Tests críticos de seguridad/dinero NO son negociables. |
| P2 | **SPECs > bitácora de prompts.** El profesor confirmó que los SPECs son la evidencia académica principal; la bitácora `docs/prompts/sprint-X.md` **NO es entregable**. | No proponer crear/actualizar la bitácora. El esfuerzo va a calidad/completitud de specs (secciones faltantes, decisiones, changelog, trazabilidad). |
| P3 | **Cadencia SDD: lotes + hitos.** El usuario produce mejor cuando trabajamos en lotes lógicos con validación en hitos significativos (compila / mvn verify / E2E manual), no archivo-por-archivo. | No micro-checkpoint. Producí un lote entero, reportá lo hecho + cómo verificarlo, esperá feedback. |
| P4 | **`APRENDIZAJES.md` al cierre de cada HU/Día.** Es la bitácora personal del usuario (no del proyecto). | Al cerrar una HU, proponer una sección nueva en primera persona, estilo Día 0/Día 1 del archivo. Headers por tema, **bold** los insights clave + por qué. |
| P5 | **No commits autónomos.** El humano firma todos los commits. El agente prepara mensajes (archivos en `%TEMP%` o pegados al chat) listos para `git commit -F`. | Cada vez que produzcas código, dejá el mensaje del commit en un archivo limpio (sin sangría — el here-string de PS coló espacios en HU-F01, ojo). |
| P6 | **Co-author trailer obligatorio** en todo commit asistido por IA. CONVENTIONS §11.6. | `Co-authored-by: <nombre-agente> <noreply@anthropic.com>` (Claude) o el dominio del proveedor (Codex: `<noreply@openai.com>` o similar). El humano puede unificar el trailer si prefiere — preguntale. |
| P7 | **Inconsistencias entre docs maestros: PARÁ y preguntá.** | No "arreglar silenciosamente". El humano decide cuál vale; queda como decisión `Dxx`. Ver D1 (interface naming con/sin `I`) como ejemplo. |
| P8 | **Branch protection en GitHub está deshabilitada deliberadamente** por plazo (decisión registrada). | No sugerir reactivarla salvo pedido explícito. |

---

## Decisiones locked (NO override)

Cada HU acumula decisiones `Dxx` en su `plan.md`. Antes de codear, leelas. Las más
transversales (aplican incluso fuera de la HU original):

- **HU-F01 D1**: Interfaces inter-módulo **sin prefijo `I`**. `Notifier` (no `INotification`),
  `Auditor` (no `IAudit`), `BalanceInitializer` (no `IBalanceInitializer`).
- **HU-F01 D10/D14**: Códigos de error en SCREAMING_SNAKE como `message` de la constraint;
  el `GlobalExceptionHandler` los mapea a texto humano via `ValidationMessages`. Cuando
  hay **un solo fieldError**, su código sube al `error` top-level del `ErrorResponse`.
- **HU-F01 D13**: BCrypt cost **12** explícito (`new BCryptPasswordEncoder(12)`). El default
  de Spring es 10. Tests assertan `password_hash.startsWith("$2a$12$")`.
- **HU-F01 D16**: Perfil `test` usa **Postgres del docker-compose en `localhost:5433/bloomtrade_test`**,
  NO Testcontainers. Razón: incompatibilidad de `docker-java 1.19.x` con el pipe
  `dockerDesktopLinuxEngine` de Docker Desktop reciente en Windows. NO intentar pelearse
  con Testcontainers; aceptar el pivot.
- **HU-F01 D17**: Coverage 60% aceptado por plazo.
- **HU-F02 D18**: `/refresh` y `/logout` **DIFERIDOS** a mini-HU post-MVP. NO los implementes
  en este bundle aunque la SPEC los mencione. Lo que se construye: `/login`, `/mfa/verify`,
  `/mfa/resend`, `JwtAuthenticationFilter`, AuthContext frontend, ProtectedRoute,
  interceptor 401→/login (sin refresh transparente).

---

## Reglas duras (de CLAUDE.md, resumidas)

- **Una HU = una rama** (`feat/HU-FXX-...`).
- **Conventional Commits** + `refs HU-FXX` + spec path en el footer.
- **Co-author trailer** en todo commit asistido por IA (P6).
- **Squash and merge** al pasar a `main`.
- **BigDecimal** para todo monto. NUMERIC(19,N) en BD. Nunca `double`/`float`.
- **Constructor injection**, nunca `@Autowired` en fields.
- **BCrypt** para passwords. Nunca SHA1/MD5/plaintext.
- **Migraciones Flyway** mergeadas son inmutables. Para cambios: V(n+1).
- **No catch genérico** de `Exception` salvo en `GlobalExceptionHandler`.
- **No `@Data` ni `@AllArgsConstructor`** en entidades JPA (rompe equals/hashCode).
- **No agregar libs** sin actualizar STACK.md en el mismo PR.
- **No exponer entidades JPA** en controllers — siempre DTO.
- **No tocar código de migraciones de HU previas** sin discusión.

---

## Setup del entorno (Windows del usuario)

- **JDK 21** (Temurin) instalado y en PATH.
- **Maven Wrapper** en `backend/mvnw[.cmd]` — usar siempre el wrapper, no `mvn` global.
- **Node 20.x + npm 10.x** instalados.
- **Docker Desktop** corriendo. Compose v2 (`docker compose`).
- **Postgres del compose** en `localhost:5433/bloomtrade` y `bloomtrade_test`.
- **JWT_SECRET** en `.env` debe ser ≥32 bytes. `JwtService` falla al arrancar si no.

PowerShell 5.1 es el shell primario. Algunas mañas:
- No usar `&&` ni `||`. Encadenar con `;` o `if ($?) { ... }`.
- Para multi-line: here-strings `@'...'@` con el cierre en columna 0.
- `curl` en PS es alias de `Invoke-WebRequest`; para curl real usar `curl.exe`.

---

## Memoria local del agente (Claude-específica, no portable)

Las preferencias P1–P8 de arriba están consolidadas de la memoria local de Claude en
`C:\Users\juang\.claude\projects\K--Repos-BloomTrade\memory\`. Esa memoria **no es
portable** — Codex/Cursor/etc. no la ven. Por eso este `AGENTS.md` es la fuente
autoritativa de las preferencias cross-agente. Si descubrís una preferencia nueva del
usuario durante una sesión, **actualizá esta sección** (no solo tu memoria local).

---

## Cuando el agente cambia (Claude ↔ Codex ↔ Cursor)

1. **El agente saliente** actualiza la sección "Trabajo activo" + "Cómo continuar"
   con el estado exacto. Confirma con el humano que git refleja el estado correcto.
2. **El agente entrante** lee este `AGENTS.md` primero, después `CLAUDE.md`, después
   los maestros, después spec/plan/tasks de la HU. Confirma con el humano qué tarea
   sigue antes de codear.
3. **El humano no debe** tener que re-explicar decisiones tomadas — todas están en
   `plan.md` de la HU correspondiente y en este archivo. Si el agente entrante
   pregunta por una decisión ya tomada, redirigilo al doc.
