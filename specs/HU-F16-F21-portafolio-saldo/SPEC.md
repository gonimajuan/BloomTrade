# spec.md — Consultar portafolio y saldo (bundle HU-F16 + HU-F21)

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F16 (BT-20 en Jira) + HU-F21 (BT-25 en Jira) |
| Sprint | 2 |
| Prioridad MoSCoW | Must (ambas) |
| Estado | Draft |
| Autor | Juan |
| Fecha creación | 2026-05-24 |
| Última actualización | 2026-05-24 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 8 |
| HUs predecesoras | HU-F09 (compra Market, PR #6) + HU-F10 (venta Market, PR #7) — ambas mergeadas en `main`. El `PortfolioService` que F16+F21 expone ya existe completo con `getBalance(userId)` y `getPositions(userId)` read-only. |
| Modalidad | **SPEC bundle**: dos HUs muy small (~30% del esfuerzo de F09 cada una) que comparten andamio (PortfolioService), el mismo módulo arquitectónico (Portfolio), el mismo controller HTTP nuevo (PortfolioController), la misma página frontend (`/portfolio`), el mismo audit event family (`PORTFOLIO_*`). Splitting en 2 specs separados sería ceremonia sin valor. Validado con humano 2026-05-24. |

---

## 2. Historia(s) de usuario

### HU-F16 — Consultar portafolio

**Como** inversionista, **quiero** ver de un vistazo todas mis posiciones abiertas con su cantidad, costo promedio, precio actual de mercado y P&L unrealized, **para** evaluar el estado financiero de mi cartera y tomar decisiones de trading informadas.

### HU-F21 — Consultar saldo disponible

**Como** inversionista, **quiero** consultar mi saldo disponible en USD en cualquier momento, **para** conocer mi poder de compra antes de colocar nuevas órdenes y verificar que las acreditaciones por ventas se reflejaron correctamente.

### Resumen del alcance del bundle

Esta spec cubre **dos endpoints HTTP read-only** sobre el módulo Portfolio + **una página frontend `/portfolio`** que los consume:

1. **`GET /api/v1/portfolio/positions`** (HU-F16) — devuelve el listado de posiciones del usuario autenticado, enriquecido con precio actual de mercado y P&L unrealized (mark-to-market). Adicionalmente devuelve una sección `pendingOrders` con las órdenes `PENDING+alpacaOrderId` (encoladas en Alpaca, no liquidadas todavía).
2. **`GET /api/v1/portfolio/balance`** (HU-F21) — devuelve el saldo USD actual del usuario autenticado.
3. **Frontend `/portfolio`** — página única con tres secciones: (a) card de saldo arriba, (b) tabla de posiciones en el medio con P&L color-coded, (c) panel colapsable de "Órdenes en cola" abajo cuando hay PENDING.

### Decisiones cerradas pre-redacción (no se discuten en el plan)

- **C1** — Slug del bundle: `HU-F16-F21-portafolio-saldo` (consistente con bundles previos `HU-F02-F03-login-mfa` y `HU-F04-F20-perfil-notificaciones`).
- **C2** — Dos endpoints separados, NO un único `GET /portfolio` agregado. Razón: HUs distintas a nivel de spec académica, frontend puede re-fetchear balance independiente tras una operación de trading sin gastar el round-trip de posiciones (que es más caro por el mark-to-market). Trade-off aceptado: 2 round-trips iniciales al cargar `/portfolio`.
- **C3** — Mark-to-market con **fallback elegante** sobre el endpoint de posiciones. Si MarketDataAdapter responde para un ticker, el DTO incluye `currentPrice`, `marketValue`, `unrealizedPnL`, `unrealizedPnLPct`. Si falla (timeout, Alpaca down, ticker inexistente para data API), esos 4 campos son `null` y un flag top-level `marketDataAvailable: false | true | "partial"` indica el estado. El frontend muestra un banner amarillo cuando hay degradación. Razón: balance entre UX completa y resiliencia.
- **C4** — Incluir órdenes `PENDING+alpacaOrderId` como sección separada `pendingOrders[]` en el response de `/positions`. Razón: mitiga la deuda viva #8/#12 documentada en AGENTS.md (saldo debitado en BUY queued / posición decrementada en SELL queued, sin reflejo visible al usuario hasta que Alpaca filee). Sin esta sección, el usuario percibe el saldo "perdido" o la posición "vendida pero no acreditada" como un bug. **Solo PENDING con `alpacaOrderId IS NOT NULL`** — órdenes en estado intermedio (en `placeOrderTx` antes de submit) NO se exponen.
- **C5** — Sin paginación. MVP single-user con espacio de tickers acotado a NYSE/NASDAQ y portafolio típico esperado <20 posiciones. Si en futuro se agregan los otros 3 mercados o multi-user, paginación queda como HU de optimización post-MVP.
- **C6** — Solo USD. Consistente con F09/F10. Multi-currency es un módulo distinto fuera del MVP.
- **C7** — Reuso del `PortfolioService` existente. NO se crea nuevo servicio. Se agregan métodos read-only adicionales si hace falta (ver §8.2). Razón: F09+F10 ya dejaron `getBalance(userId)` y `getPositions(userId)` listos.
- **C8** — Frontend: una sola página `/portfolio` con scroll, sin tabs. Las 3 secciones (balance / posiciones / pendingOrders) son complementarias, no excluyentes. Tabs introducen fricción de clicks sin beneficio.

### Decisiones diferidas a `plan.md`

- **D-MARKET-DATA-FANOUT** — estrategia de fan-out de N llamadas a `MarketDataAdapter.getLatestPrice(ticker)`: secuencial vs paralelo (CompletableFuture sobre el executor por defecto o WebClient reactivo). Trade-off: paralelo mejora latencia pero N=20 llamadas concurrentes a Alpaca data API podría chocar con rate limits (verificar headers de Alpaca).
- **D-MARKET-DATA-TIMEOUT** — timeout por ticker vs timeout global del endpoint. Hard requirement: el endpoint completo NO puede tardar más de 5 segundos (NFR-PERF-PORTFOLIO §10).
- **D-PARTIAL-FAILURE-POLICY** — si M de N tickers fallan: ¿devolver todos los DTOs sin `currentPrice` (all-or-nothing, más simple) o devolver los exitosos con precio y los M fallidos con `currentPrice=null` (información parcial, más útil). C3 ya cerró que `marketDataAvailable` puede ser `"partial"` — D-PARTIAL-FAILURE-POLICY elige el mecanismo.
- **D-PENDING-ORDERS-QUERY** — query directo de `OrderRepository.findByUserIdAndStatusAndAlpacaOrderIdNotNull(...)` desde el controller vs nuevo método `PortfolioService.getPendingOrders(userId)` que coordine. Trade-off: controller-direct rompe la simetría con `/balance` y `/positions` que pasan por service.
- **D-CONTROLLER-STRUCTURE** — `PortfolioController` nuevo (alineado con módulo Portfolio del ARCHITECTURE.md §3) vs extender `OrderController` con paths nuevos. Inclinación fuerte hacia controller nuevo por separación de responsabilidades.
- **D-FRONTEND-LAYOUT** — orden vertical de secciones (balance → posiciones → pending) confirmado en C8, pero **diseño visual** del card de saldo (compacto inline vs hero card grande), y de la tabla (DataGrid vs HTML table simple) queda para plan.
- **D-PNL-COLOR-CONVENTION** — verde para P&L positivo, rojo para negativo, gris para zero/null. Estándar broker pero confirmar tokens del design system del proyecto.
- **D-REFRESH-MECHANISM** — botón manual de refresh vs auto-refresh on focus vs polling intervalado. Inclinación: manual + auto-refresh on focus (React Query default), sin polling por costo de N llamadas a market data.
- **D-AUDIT-EVENTS** — qué eventos `PORTFOLIO_*` registrar. Read-only no es auditable por defecto, pero `PORTFOLIO_VIEWED` podría tener valor para análisis de engagement. Decisión: NO auditar reads (consistente con `/me` de HU-F04). Si compliance lo pide, queda como deuda.

---

## 3. Contexto y dependencias

### Por qué importa

HU-F16 + HU-F21 son **el primer feedback loop visible al usuario** sobre los efectos de F09 y F10. Sin ellas, el usuario compra/vende pero no tiene cómo ver el resultado consolidado fuera del email de confirmación o de consultar la BD directamente. Son la pieza que cierra el bucle de UX: **acción de trading → consulta de portafolio → próxima acción de trading**.

Académicamente, son las HUs más simples del Sprint 2 porque:
- Sin transacciones complejas (read-only).
- Sin eventos asíncronos (no se disparan listeners).
- Sin migraciones BD.
- Sin Alpaca trading API (solo Alpaca data API, opcional con fallback).
- Sin nuevos audit events (consultas no se auditan).
- Sin notificaciones por email.

Lo que SÍ aportan es **un ejercicio limpio del módulo Portfolio del ARCHITECTURE.md §3** que en F09/F10 quedó como dependencia interna sin endpoint público.

### Dependencias técnicas

- **HU-F09 + HU-F10 mergeadas en `main`** — verificado en `git log origin/main`: commit `e5a8943` (PR #7 HU-F10) sobre `1bab23b` (PR #6 HU-F09). Sin esto, no hay `PortfolioService` ni datos en `app.positions`/`app.user_balances` para mostrar.
- **`PortfolioService` existente** con métodos read-only ya implementados:
  - `getBalance(userId): UserBalance` (de F09).
  - `getPositions(userId): List<Position>` (de F09).
  - `findBalanceProjectionByUserId(...)` (de F09, projection-only para evitar Hibernate L1 cache — D26 F09).
- **`MarketDataAdapter.getLatestPrice(ticker): BigDecimal`** existente (de F09 Lote B). Encapsula la llamada a Alpaca data API con `@Retry(name="alpacaData")` configurado. Lanza `MarketDataUnavailableException` cuando timeout o 5xx persistente.
- **`OrderRepository`** existente (de F09) — se agrega un método derivado `findByUserIdAndStatusAndAlpacaOrderIdNotNullOrderBySubmittedAtDesc(...)` para C4.
- **`JwtAuthenticationFilter`** ya popula `@AuthenticationPrincipal User` en endpoints autenticados (de HU-F02). Ambos endpoints del bundle son autenticados.

### Variables de entorno nuevas

**Ninguna.** F09 ya configuró `ALPACA_DATA_BASE_URL` y `ALPACA_API_KEY`/`SECRET` para data API.

### Migraciones BD nuevas

**Ninguna.** Solo lectura sobre tablas existentes (`app.user_balances`, `app.positions`, `app.orders`).

### Features que dependen de esta

- **HU-F18 Dashboard** (Día 9) — consumirá `GET /portfolio/positions` y `GET /portfolio/balance` para los widgets principales. Diseño del response del bundle debe contemplar este consumer.
- **HU-F17 Historial de operaciones** (post-MVP) — independiente; lee `app.orders` con paginación.

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | Único actor con JWT válido | Realiza la consulta. Ve solo SUS datos (filter por `user_id`). |
| `JwtAuthenticationFilter` | Componente seguridad (HU-F02) | Resuelve `@AuthenticationPrincipal` a partir del header `Authorization: Bearer <jwt>`. |
| `PortfolioService` | Servicio de dominio (existente) | Lee `app.user_balances` y `app.positions`. |
| `MarketDataAdapter` | Adapter externo (F09) | Devuelve precio actual por ticker. Falla parcial es aceptable (C3). |
| `OrderRepository` | Repository (F09) | Lee órdenes PENDING para `pendingOrders[]`. |
| `Auditor` | Auditoría (no usado en este bundle) | NO se emiten eventos para reads (decisión §2 D-AUDIT-EVENTS). |

### Precondiciones

- Usuario tiene cuenta activa (HU-F01).
- Usuario tiene sesión activa con JWT válido no expirado (HU-F02). Endpoints rechazan 401 sin JWT.
- Para que `/positions` devuelva datos significativos, el usuario debe haber realizado al menos un BUY ejecutado (F09). Sin posiciones, devuelve `{ positions: [], pendingOrders: [], marketDataAvailable: true }` con 200 OK (estado vacío válido).
- Para `/balance`, el usuario tiene fila en `app.user_balances` por bootstrap automático de HU-F01 (`BalanceInitializer` listener).

### Postcondiciones

- **Ninguna mutación.** Sin escrituras a BD. Sin emails. Sin eventos.
- El usuario obtiene la información solicitada o un error claro (401 sin sesión, 502 si Alpaca data API down completo Y se decide en D-PARTIAL-FAILURE-POLICY que all-or-nothing aplica al endpoint entero — pero la inclinación es responder 200 con `marketDataAvailable=false`).

---

## 5. Flujos

### 5.1 Flujo principal — HU-F16 GET /portfolio/positions (mark-to-market exitoso)

```
1. Usuario navega a /portfolio en frontend → React Query dispara `usePortfolioPositions()` y `useBalance()` en paralelo.
2. Hook `usePortfolioPositions` hace GET /api/v1/portfolio/positions con Authorization: Bearer <jwt>.
3. JwtAuthenticationFilter valida JWT y popula SecurityContext con User.
4. PortfolioController.getPositions(@AuthenticationPrincipal User user):
   4.1. Llama PortfolioService.getPositions(user.getId()) → List<Position> (de app.positions, qty > 0).
   4.2. Llama PortfolioService.getPendingOrders(user.getId()) → List<Order> (status=PENDING AND alpaca_order_id IS NOT NULL).
   4.3. Si hay posiciones: fan-out a MarketDataAdapter.getLatestPrice(ticker) por cada ticker único (ver D-MARKET-DATA-FANOUT).
   4.4. Mapea cada Position + currentPrice (o null si falla) a PositionDto via PortfolioMapper.
   4.5. Mapea cada Order pendiente a PendingOrderDto.
   4.6. Calcula marketDataAvailable: true (todas exitosas) | "partial" (algunas null) | false (todas null).
   4.7. Construye PortfolioPositionsResponse{ positions, pendingOrders, marketDataAvailable, fetchedAt }.
5. Controller responde 200 OK con el JSON.
6. Frontend renderiza:
   - Tabla posiciones con ticker, qty, avgCost, currentPrice (o "—"), marketValue (o "—"), unrealizedPnL (verde/rojo/gris).
   - Sección "Órdenes en cola" colapsable si pendingOrders.length > 0.
   - Banner amarillo "Precios de mercado no disponibles" si marketDataAvailable=false; banner verde con asterisco si "partial".
```

### 5.2 Flujo principal — HU-F21 GET /portfolio/balance

```
1. Hook useBalance() hace GET /api/v1/portfolio/balance con Bearer <jwt>.
2. JwtAuthenticationFilter valida y popula SecurityContext.
3. PortfolioController.getBalance(@AuthenticationPrincipal User user):
   3.1. Llama PortfolioService.getBalance(user.getId()) → UserBalance.
   3.2. Mapea via PortfolioMapper a BalanceResponse{ balance: "1234.56", currency: "USD", lastUpdatedAt: <Instant> }.
4. Controller responde 200 OK.
5. Frontend renderiza card con saldo formateado (Intl.NumberFormat es-CO con 2 decimales).
```

### 5.3 Flujo alterno — `/positions` con Alpaca data API caído (mark-to-market degradado)

```
4.3'. MarketDataAdapter.getLatestPrice(...) lanza MarketDataUnavailableException tras 3 retries y timeout.
4.3''. El método de fan-out captura la excepción por ticker (no propaga al endpoint).
4.4'. PositionDto se construye con currentPrice=null, marketValue=null, unrealizedPnL=null, unrealizedPnLPct=null.
4.6'. marketDataAvailable = false (si TODAS fallaron) o "partial" (si SOME fallaron).
5'. Controller responde 200 OK (no 502).
6'. Frontend muestra banner amarillo + tabla con celdas "—" en columnas afectadas. P&L oculto.
```

### 5.4 Flujo alterno — Usuario nuevo sin posiciones

```
4.1''. PortfolioService.getPositions(...) devuelve [].
4.2''. PortfolioService.getPendingOrders(...) devuelve [].
4.3''. No hay tickers → no se invoca MarketDataAdapter.
4.6''. marketDataAvailable = true (consistencia: no se intentó nada y no falló nada).
5''. Controller responde 200 OK con { positions: [], pendingOrders: [], marketDataAvailable: true, fetchedAt: ... }.
6''. Frontend muestra empty state: "Aún no tienes posiciones. Empieza con tu primera compra." con CTA a /trade.
```

### 5.5 Flujo de error — Sin JWT o JWT expirado

```
3'. JwtAuthenticationFilter rechaza con 401 AUTHENTICATION_REQUIRED.
6'''. Frontend interceptor 401 → redirige a /login (heredado de HU-F02 D23).
```

### 5.6 Flujo informativo — Órdenes en cola visibles

```
Pre: usuario hizo BUY de AAPL fuera de horario NYSE → orden quedó PENDING+alpacaOrderId (D29 F09) → saldo ya fue debitado, posición NO existe todavía.
1-4. Mismo flujo 5.1.
4.2'. PortfolioService.getPendingOrders devuelve [{ orderId, clientOrderId, ticker: "AAPL", side: "BUY", quantity: 5, submittedAt }].
4.5'. PendingOrderDto incluye los 5 campos arriba + estimatedTotal (si está calculado al INSERT) o null.
6.f. Frontend muestra sección "Órdenes en cola (1)" colapsable, abierta por defecto si count > 0, con badge naranja "Esperando apertura de mercado".
```

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

| Método | Path | Auth | Descripción |
|---|---|---|---|
| GET | `/api/v1/portfolio/positions` | Bearer JWT | Posiciones del usuario con mark-to-market + órdenes pendientes |
| GET | `/api/v1/portfolio/balance` | Bearer JWT | Saldo USD disponible del usuario |

### 6.2 `GET /api/v1/portfolio/positions`

#### Request

```
GET /api/v1/portfolio/positions
Authorization: Bearer <jwt>
```

Sin query params en MVP. (Futuro: `?includeMarketData=false` para skip mark-to-market si performance lo demanda.)

#### Response 200 OK

```yaml
components:
  schemas:
    PortfolioPositionsResponse:
      type: object
      required: [positions, pendingOrders, marketDataAvailable, fetchedAt]
      properties:
        positions:
          type: array
          items: { $ref: '#/components/schemas/PositionDto' }
        pendingOrders:
          type: array
          items: { $ref: '#/components/schemas/PendingOrderDto' }
        marketDataAvailable:
          type: string
          enum: ["true", "false", "partial"]
          description: |
            Estado del mark-to-market:
            - "true": todas las posiciones tienen currentPrice/marketValue/unrealizedPnL poblados (o no hay posiciones).
            - "partial": algunas posiciones tienen los campos en null (Alpaca data API devolvió error para esos tickers).
            - "false": ninguna posición tiene precio actual (Alpaca data API totalmente caído).
        fetchedAt:
          type: string
          format: date-time
          description: Instante UTC de generación del response. Usado por frontend para mostrar "Actualizado hace Xs".

    PositionDto:
      type: object
      required: [ticker, quantity, avgCost, costBasis, currency]
      properties:
        ticker:
          type: string
          example: "AAPL"
        quantity:
          type: integer
          minimum: 1
          example: 10
        avgCost:
          type: string
          description: BigDecimal stringified, precio promedio de adquisición (incluye comisiones prorrateadas al cost basis). Scale=2.
          example: "189.45"
        costBasis:
          type: string
          description: BigDecimal stringified, quantity × avgCost. Scale=2.
          example: "1894.50"
        currency:
          type: string
          enum: ["USD"]
          example: "USD"
        currentPrice:
          type: [string, "null"]
          description: BigDecimal stringified del último precio según Alpaca data API. null si market data no disponible para este ticker.
          example: "193.20"
        marketValue:
          type: [string, "null"]
          description: BigDecimal stringified, quantity × currentPrice. null si currentPrice es null.
          example: "1932.00"
        unrealizedPnL:
          type: [string, "null"]
          description: BigDecimal stringified, marketValue − costBasis. null si marketValue es null. Puede ser negativo.
          example: "37.50"
        unrealizedPnLPct:
          type: [string, "null"]
          description: BigDecimal stringified, (unrealizedPnL / costBasis) × 100. Scale=2. null si marketValue es null.
          example: "1.98"

    PendingOrderDto:
      type: object
      required: [orderId, clientOrderId, ticker, side, quantity, submittedAt]
      properties:
        orderId:
          type: string
          format: uuid
          description: PK de app.orders.
        clientOrderId:
          type: string
          format: uuid
          description: Idempotency key generado por el frontend en el momento de la submisión (heredado F09 D25).
        ticker:
          type: string
          example: "AAPL"
        side:
          type: string
          enum: [BUY, SELL]
        quantity:
          type: integer
          minimum: 1
        submittedAt:
          type: string
          format: date-time
          description: Instante UTC del INSERT inicial de la orden.
        quotedTotal:
          type: [string, "null"]
          description: BigDecimal stringified, el `quoted_total` snapshotado al momento del placeOrder. Para BUY = lo debitado del saldo. Para SELL = lo que se acreditará al fileear. null si no está poblado.
```

#### Ejemplo de response (happy path)

```json
{
  "positions": [
    {
      "ticker": "AAPL",
      "quantity": 10,
      "avgCost": "189.45",
      "costBasis": "1894.50",
      "currency": "USD",
      "currentPrice": "193.20",
      "marketValue": "1932.00",
      "unrealizedPnL": "37.50",
      "unrealizedPnLPct": "1.98"
    },
    {
      "ticker": "MSFT",
      "quantity": 5,
      "avgCost": "412.00",
      "costBasis": "2060.00",
      "currency": "USD",
      "currentPrice": "408.50",
      "marketValue": "2042.50",
      "unrealizedPnL": "-17.50",
      "unrealizedPnLPct": "-0.85"
    }
  ],
  "pendingOrders": [
    {
      "orderId": "11111111-2222-3333-4444-555555555555",
      "clientOrderId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      "ticker": "TSLA",
      "side": "BUY",
      "quantity": 3,
      "submittedAt": "2026-05-24T22:30:15Z",
      "quotedTotal": "705.30"
    }
  ],
  "marketDataAvailable": "true",
  "fetchedAt": "2026-05-25T14:02:11Z"
}
```

### 6.3 `GET /api/v1/portfolio/balance`

#### Request

```
GET /api/v1/portfolio/balance
Authorization: Bearer <jwt>
```

#### Response 200 OK

```yaml
components:
  schemas:
    BalanceResponse:
      type: object
      required: [balance, currency, lastUpdatedAt]
      properties:
        balance:
          type: string
          description: BigDecimal stringified, saldo disponible. Scale=2.
          example: "8345.67"
        currency:
          type: string
          enum: [USD]
          example: "USD"
        lastUpdatedAt:
          type: string
          format: date-time
          description: Instante UTC del último UPDATE sobre la fila (de `updated_at` de app.user_balances).
```

#### Ejemplo

```json
{ "balance": "8345.67", "currency": "USD", "lastUpdatedAt": "2026-05-24T20:15:33Z" }
```

### 6.4 Códigos de error compartidos por ambos endpoints

| HTTP | Código aplicación | Cuándo |
|---|---|---|
| 401 | `AUTHENTICATION_REQUIRED` | Sin JWT, JWT inválido o JWT expirado. Heredado de F02. |
| 403 | `ACCOUNT_NOT_ACTIVE` | Usuario con `status != ACTIVE`. Heredado de F02. (Probablemente no aplique a portfolio dado que el usuario no podría haber operado, pero el filter lo emite igual.) |
| 500 | `INTERNAL_ERROR` | Error inesperado. Logged + audit (si aplica). No expone detalles. |

> **Importante:** `502 MARKET_DATA_UNAVAILABLE` NO se emite en `/positions`. Se traduce a `marketDataAvailable=false` con HTTP 200 (C3). Solo se emitiría si la decisión D-PARTIAL-FAILURE-POLICY revoca C3 — no se contempla.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Ninguna.** F16+F21 son consultas read-only sobre tablas existentes (`app.user_balances`, `app.positions`, `app.orders`) ya migradas por V3 (HU-F01 / F02) + V5 (HU-F09).

### 7.2 Modificaciones a tablas existentes

**Ninguna.**

### 7.3 Índices nuevos sugeridos (no bloqueantes)

- `app.orders (user_id, status) WHERE alpaca_order_id IS NOT NULL` — partial index para optimizar `getPendingOrders` cuando el volumen de órdenes históricas crezca. Diferido a post-MVP; sin él, el seq scan sobre `app.orders` filtrado en memoria es aceptable para los volúmenes de prueba (decisión registrada como deuda en plan).

### 7.4 Datos semilla

**Ninguno nuevo.**

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados (ARCHITECTURE.md §3)

| Módulo | Rol en F16+F21 |
|---|---|
| **Portfolio** | Owner del bundle. Expone los 2 endpoints. `PortfolioController` (nuevo) + extensiones a `PortfolioService` (read-only). |
| **Trading** | Provee `OrderRepository` para consultar `pendingOrders`. NO se modifica. |
| **Integration** | `MarketDataAdapter` consumido por Portfolio para mark-to-market. NO se modifica (reutilización directa de F09). |
| **Auth** | `JwtAuthenticationFilter` resuelve `@AuthenticationPrincipal`. NO se modifica. |
| **Notification** | NO involucrado (sin emails). |
| **Audit** | NO involucrado (reads no se auditan — D-AUDIT-EVENTS). |
| **Reporting** | NO involucrado (post-MVP). |
| **Configuration** | NO involucrado. |
| **Persistence** | Repositorios `UserBalanceRepository`, `PositionRepository`, `OrderRepository` (todos existentes). |

### 8.2 Componentes nuevos por crear

- **Backend (módulo Portfolio):**
  - `co.edu.unbosque.bloomtrade.portfolio.web.PortfolioController` — REST controller con 2 endpoints.
  - `co.edu.unbosque.bloomtrade.portfolio.dto.PortfolioPositionsResponse` — DTO response del endpoint /positions.
  - `co.edu.unbosque.bloomtrade.portfolio.dto.PositionDto` — DTO item de positions[].
  - `co.edu.unbosque.bloomtrade.portfolio.dto.PendingOrderDto` — DTO item de pendingOrders[].
  - `co.edu.unbosque.bloomtrade.portfolio.dto.BalanceResponse` — DTO response del endpoint /balance.
  - `co.edu.unbosque.bloomtrade.portfolio.web.PortfolioMapper` — Mapper manual (BigDecimal→String, ZonedDateTime→Instant). Justificación de manual sobre MapStruct: igual que F09/F10, los mappings con BigDecimal stringification + cálculos compuestos (marketValue, unrealizedPnL) son más legibles imperativos.
  - `co.edu.unbosque.bloomtrade.portfolio.service.MarketDataOrchestrator` (o método interno en `PortfolioService` — D-MARKET-DATA-FANOUT decide) — coordina fan-out a `MarketDataAdapter` con fallback.

- **Backend (extensiones a existentes):**
  - `PortfolioService.getPendingOrders(userId): List<Order>` — nuevo método read-only.
  - `OrderRepository.findByUserIdAndStatusAndAlpacaOrderIdNotNullOrderBySubmittedAtDesc(userId, status)` — query derivado.
  - Posiblemente `PortfolioService.getPortfolioWithMarketData(userId): PortfolioPositionsResponse` — wrapper que orquesta `getPositions + getPendingOrders + fan-out market data`. Decisión D-CONTROLLER-STRUCTURE.

- **Frontend (módulo `features/portfolio/`):**
  - `frontend/src/features/portfolio/PortfolioPage.tsx` — página completa con 3 secciones.
  - `frontend/src/features/portfolio/components/BalanceCard.tsx` — card de saldo.
  - `frontend/src/features/portfolio/components/PositionsTable.tsx` — tabla de posiciones con P&L color-coded.
  - `frontend/src/features/portfolio/components/PendingOrdersPanel.tsx` — panel colapsable.
  - `frontend/src/features/portfolio/components/MarketDataBanner.tsx` — banner condicional.
  - `frontend/src/features/portfolio/hooks/usePortfolioPositions.ts` — React Query hook.
  - `frontend/src/features/portfolio/hooks/useBalance.ts` — React Query hook.
  - `frontend/src/features/portfolio/api/portfolioApi.ts` — wrappers HTTP.
  - `frontend/src/types/api.ts` — types extendidos: `PortfolioPositionsResponse`, `PositionDto`, `PendingOrderDto`, `BalanceResponse`.
  - `frontend/src/App.tsx` — ruta nueva `/portfolio` (protegida).
  - `frontend/src/components/AppHeader.tsx` — link "Portafolio".
  - `frontend/src/lib/messages.es.ts` — +copys para empty state, banner degradado, sección pending.

### 8.3 Tácticas Bass aplicadas (ARCHITECTURE.md §6)

- **TAC-D5 (Defensa en profundidad — fallback degradado):** mark-to-market con fallback a `currentPrice=null` cuando MarketDataAdapter falla. El endpoint sigue respondiendo 200 con info parcial en lugar de propagar 502 al cliente. UX no se rompe ante caídas de Alpaca data API.
- **TAC-S2 (Authenticated request scope):** `@AuthenticationPrincipal User user` filtra todo por `userId` automáticamente. Imposible filtrar cross-user vía manipulación de query params (no los hay).
- **TAC-P2 (Pre-computar resultados):** el `costBasis` (qty × avgCost) se calcula en el mapper en cada request. Si volumen crece, considerar persistir en `app.positions` como columna derivada — diferido.
- **TAC-D2 (Retry, heredado de F09):** `MarketDataAdapter` ya tiene `@Retry(name="alpacaData")` con 3 retries × backoff exponencial. F16 lo hereda sin tocar.

### 8.4 Patrones de diseño (Skills 1-5)

- **Adapter (de F09):** `MarketDataAdapter` desacopla la lógica de portfolio de la API concreta de Alpaca.
- **DTO (de F01+):** estricta separación entity ↔ response.
- **Repository (de F01+):** `PositionRepository`, `UserBalanceRepository`, `OrderRepository`.
- **Strategy (latente):** la elección entre serial vs paralelo en fan-out market data podría modelarse como Strategy si en el futuro queremos cambiar dinámicamente; MVP usa una sola estrategia (D-MARKET-DATA-FANOUT define cuál).

---

## 9. Efectos colaterales

### 9.1 Auditoría

**Ninguno.** Consultas read-only no se auditan (consistente con HU-F04 `/me`, HU-F06 `/subscription/status`). Si compliance lo pidiera, se agrega como deuda.

### 9.2 Notificaciones

**Ninguna.** El bundle no envía emails.

### 9.3 Logs

- `INFO` al entrar a cada endpoint con `userId`, ticker count (positions) y latency total.
- `WARN` por cada ticker que falle mark-to-market con razón (timeout, 5xx, 404). Útil para detectar drift de tickers entre BloomTrade y Alpaca data API.
- `ERROR` si el endpoint completo cae (excepción no manejada) — handler global lo registra.

### 9.4 Métricas (Micrometer)

Sin métricas custom en MVP. Reutiliza los timers de Spring Boot Actuator sobre `http.server.requests` con tags `uri=/api/v1/portfolio/positions` y `/balance`. La latencia de `marketDataAdapter` ya está instrumentada en F09.

### 9.5 Eventos

**Ninguno.** No se publican eventos Spring.

---

## 10. Atributos de calidad aplicables

| Atributo (ARCHITECTURE.md §4) | Cómo aplica a F16+F21 | NFR concreto |
|---|---|---|
| **Performance** | El fan-out market data puede ser costoso (N llamadas externas). Si N=20 y cada llamada tarda hasta 1.5s, serial = 30s (inaceptable). Paralelo con timeout = ~3s. | **NFR-PERF-PORTFOLIO**: p95 de `/positions` ≤ 5 segundos con hasta 20 posiciones. p95 de `/balance` ≤ 200 ms. |
| **Availability** | Endpoints deben servir aún si Alpaca data API está caído (C3 fallback). | **NFR-AVAIL-PORTFOLIO**: `/positions` mantiene 99% de disponibilidad funcional aún con Alpaca data API en 0% disponibilidad (degrada a `marketDataAvailable=false`, sin 502 al cliente). |
| **Security** | Filter strict por `userId` desde JWT. Imposible leakar datos cross-user. Ningún field sensible adicional (no se exponen IDs internos de Alpaca, ni client secrets). | **NFR-SEC-PORTFOLIO**: test IT que confirma que un usuario A no puede ver posiciones de usuario B por ningún path. |
| **Usability** | Empty state explicativo, banner claro en degradación, P&L color-coded, formato es-CO de moneda. | Validado en E2E manual del HITO frontend. |
| **Maintainability** | Patrón consistente con HU-F09: controller delga a service, DTOs separados, mapper manual con BigDecimal stringificado, hooks React Query. | Code review verifica adherencia a CONVENTIONS.md. |
| **Testability** | `MarketDataAdapter` ya es mockeable (interface con stub). Permite test de degradación sin Alpaca real. | Tests IT con WireMock simulando Alpaca data API caído. |

---

## 11. Criterios de aceptación

### HU-F16-AC-01 — Listado de posiciones con mark-to-market exitoso

```gherkin
Dado un usuario autenticado con 2 posiciones (10 AAPL avgCost 189.45, 5 MSFT avgCost 412.00)
Y Alpaca data API responde con AAPL=193.20 y MSFT=408.50
Cuando hace GET /api/v1/portfolio/positions
Entonces recibe 200 OK con:
  - positions[0] = { ticker:"AAPL", quantity:10, avgCost:"189.45", costBasis:"1894.50", currentPrice:"193.20", marketValue:"1932.00", unrealizedPnL:"37.50", unrealizedPnLPct:"1.98", currency:"USD" }
  - positions[1] = { ticker:"MSFT", quantity:5, avgCost:"412.00", costBasis:"2060.00", currentPrice:"408.50", marketValue:"2042.50", unrealizedPnL:"-17.50", unrealizedPnLPct:"-0.85", currency:"USD" }
  - pendingOrders = []
  - marketDataAvailable = "true"
  - fetchedAt presente y reciente (<5s antes del now)
```

### HU-F16-AC-02 — Listado con Alpaca data API caído (fallback completo)

```gherkin
Dado un usuario autenticado con 2 posiciones AAPL y MSFT
Y Alpaca data API devuelve 503 a todas las llamadas (incluso tras 3 retries)
Cuando hace GET /api/v1/portfolio/positions
Entonces recibe 200 OK con:
  - positions[].currentPrice = null
  - positions[].marketValue = null
  - positions[].unrealizedPnL = null
  - positions[].unrealizedPnLPct = null
  - marketDataAvailable = "false"
Y el frontend renderiza un banner amarillo "Precios de mercado no disponibles" + tabla con "—" en columnas afectadas
```

### HU-F16-AC-03 — Listado con falla parcial de Alpaca

```gherkin
Dado un usuario con 3 posiciones AAPL, MSFT, TSLA
Y Alpaca data API responde para AAPL y MSFT pero devuelve 404 para TSLA
Cuando hace GET /api/v1/portfolio/positions
Entonces recibe 200 OK con:
  - positions[AAPL].currentPrice presente
  - positions[MSFT].currentPrice presente
  - positions[TSLA].currentPrice = null y marketValue/unrealizedPnL = null
  - marketDataAvailable = "partial"
```

### HU-F16-AC-04 — Usuario sin posiciones

```gherkin
Dado un usuario autenticado recién registrado sin ninguna posición
Cuando hace GET /api/v1/portfolio/positions
Entonces recibe 200 OK con { positions: [], pendingOrders: [], marketDataAvailable: "true", fetchedAt: ... }
Y NO se invoca a MarketDataAdapter (verificable por logs / mock interaction count)
Y el frontend muestra empty state "Aún no tienes posiciones" con CTA a /trade
```

### HU-F16-AC-05 — Órdenes pendientes visibles

```gherkin
Dado un usuario con 0 posiciones y 1 orden PENDING+alpacaOrderId (BUY 5 TSLA submitida fuera de horario)
Cuando hace GET /api/v1/portfolio/positions
Entonces recibe 200 OK con:
  - positions = []
  - pendingOrders[0] = { orderId, clientOrderId, ticker:"TSLA", side:"BUY", quantity:5, submittedAt presente, quotedTotal presente }
  - marketDataAvailable = "true"
Y el frontend muestra la sección "Órdenes en cola (1)" expandida por defecto con badge naranja "Esperando apertura de mercado"
```

### HU-F16-AC-06 — Aislamiento cross-user

```gherkin
Dado un usuario A con posiciones AAPL y un usuario B con posiciones MSFT
Cuando A hace GET /api/v1/portfolio/positions con su JWT
Entonces recibe SOLO sus posiciones AAPL (ninguna MSFT)
Y no hay forma de manipular el request para ver las de B (no hay query params de userId)
```

### HU-F16-AC-07 — Sin JWT rechaza 401

```gherkin
Dado un cliente sin Authorization header
Cuando hace GET /api/v1/portfolio/positions
Entonces recibe 401 AUTHENTICATION_REQUIRED
Y el frontend (si vino desde una sesión expirada) redirige a /login
```

### HU-F21-AC-01 — Consulta de saldo happy path

```gherkin
Dado un usuario autenticado con saldo USD 8345.67 (resultado de bootstrap 10000 - 1654.33 en operaciones)
Cuando hace GET /api/v1/portfolio/balance
Entonces recibe 200 OK con { balance: "8345.67", currency: "USD", lastUpdatedAt: <Instant del último UPDATE> }
```

### HU-F21-AC-02 — Saldo recién bootstrappeado

```gherkin
Dado un usuario recién registrado (bootstrap puso saldo USD 10000.00 por BalanceInitializer)
Cuando hace GET /api/v1/portfolio/balance
Entonces recibe 200 OK con { balance: "10000.00", currency: "USD", lastUpdatedAt: <Instant del INSERT inicial> }
```

### HU-F21-AC-03 — Aislamiento cross-user del saldo

```gherkin
Dado usuario A con saldo USD 5000 y usuario B con saldo USD 8000
Cuando A consulta /balance con su JWT
Entonces ve 5000 y NUNCA 8000, sin manera de impersonar a B
```

### HU-F21-AC-04 — Sin JWT rechaza 401

```gherkin
Dado un cliente sin Authorization header
Cuando hace GET /api/v1/portfolio/balance
Entonces recibe 401 AUTHENTICATION_REQUIRED
```

### HU-F21-AC-05 — Refresh tras venta refleja crédito

```gherkin
Dado un usuario con saldo USD 5000 y posición de 10 AAPL avgCost 190
Cuando vende 10 AAPL @ market y la orden se ejecuta con producto neto 1900 (Alpaca devuelve fill price 192, commission 2%)
Y inmediatamente después llama GET /api/v1/portfolio/balance
Entonces recibe el saldo actualizado (5000 + producto_neto) con lastUpdatedAt actualizado al UPDATE del credit
```

### HU-F16+F21-AC-AGG-01 — Página /portfolio render integral

```gherkin
Dado un usuario logueado con posiciones + pending orders + saldo populado
Cuando navega a /portfolio
Entonces ve simultáneamente (cargas paralelas vía React Query):
  - Card "Saldo disponible: USD 8.345,67" formateado es-CO arriba
  - Tabla de posiciones con cada fila mostrando P&L verde si positivo, rojo si negativo, gris si null
  - Sección "Órdenes en cola" colapsable abierta por defecto
Y el tiempo de visualización completa (ambos endpoints + render) es <6s en p95 (NFR-PERF-PORTFOLIO + 1s render budget)
```

---

## 12. UI y experiencia

### 12.1 Layout de `/portfolio`

```
┌─────────────────────────────────────────────────────┐
│ AppHeader [Dashboard][Trade][PORTAFOLIO][Premium]   │
├─────────────────────────────────────────────────────┤
│ ╔═══════════════════════════════════════════════╗   │
│ ║  Saldo disponible                              ║   │
│ ║  USD 8,345.67                                  ║   │
│ ║  Actualizado: hace 12 s                  [↻]   ║   │
│ ╚═══════════════════════════════════════════════╝   │
│                                                     │
│ Posiciones                                          │
│ [Banner amarillo si marketDataAvailable=false]      │
│ ┌─────┬───────┬───────┬────────┬─────────┬───────┐ │
│ │Tick │ Cant  │ AvgC  │ Actual │ Valor   │ P&L   │ │
│ ├─────┼───────┼───────┼────────┼─────────┼───────┤ │
│ │AAPL │   10  │189.45 │ 193.20 │ 1932.00 │ +1.98%│ │ ← verde
│ │MSFT │    5  │412.00 │ 408.50 │ 2042.50 │ -0.85%│ │ ← rojo
│ └─────┴───────┴───────┴────────┴─────────┴───────┘ │
│                                                     │
│ ▼ Órdenes en cola (1)                               │
│   • BUY 3 TSLA — submitida 24-May 22:30 — USD 705   │
│     [badge naranja "Esperando apertura"]            │
└─────────────────────────────────────────────────────┘
```

Si no hay posiciones ni pending: empty state centrado "Aún no tienes posiciones. [Realizar primera compra]" con CTA a `/trade`.

### 12.2 Wording

| Elemento | Texto |
|---|---|
| Título página | "Mi portafolio" |
| Empty state | "Aún no tienes posiciones. Empieza con tu primera compra." |
| CTA empty | "Realizar primera compra" |
| Banner amarillo | "Precios de mercado temporalmente no disponibles. Mostramos cantidad y costo promedio." |
| Banner naranja partial | "Algunos precios no se pudieron obtener. Marcados con —" |
| Sección pendientes (header) | "Órdenes en cola ({n})" |
| Badge pending | "Esperando apertura de mercado" |
| Tooltip P&L | "Pérdida o ganancia no realizada vs. costo promedio de compra" |
| Footer card saldo | "Actualizado: hace {n} s" o "Actualizado: {hora}" |
| Botón refresh | Icono ↻ con aria-label "Actualizar" |

Todos los copys van a `frontend/src/lib/messages.es.ts` con keys `portfolio.*`.

### 12.3 Formato de moneda

`Intl.NumberFormat("es-CO", { style: "currency", currency: "USD", minimumFractionDigits: 2 })` → "USD 8.345,67". Consistente con HU-F09/F10 que ya usan este formato.

### 12.4 Color de P&L

- Positivo: `text-emerald-600` (token verde del design system, consistente con confirmaciones de F09/F10).
- Negativo: `text-rose-600` (token rojo).
- Zero o null: `text-slate-500`.
- D-PNL-COLOR-CONVENTION en plan confirma tokens exactos.

### 12.5 Refresh

- React Query `refetchOnWindowFocus: true` (default) → re-fetch automático al volver a la pestaña.
- Botón `↻` en el card de saldo que invalida ambas queries (saldo + posiciones) — útil tras operar en /trade y volver.
- NO polling intervalado (costo de N llamadas a market data por sesión).

### 12.6 Responsive

- Desktop: layout descrito.
- Mobile (<768px): tabla colapsa a cards verticales por posición; banner full-width; sección pending igual con scroll horizontal si es necesario.

### 12.7 Accesibilidad

- Tabla con `<thead>` semántico + `scope="col"`.
- P&L color complementado con icono ▲/▼ (no solo color, por daltonismo).
- Empty state con CTA enfocable por teclado.
- Banner amarillo con role="status" para anuncio en lectores de pantalla.

---

## 13. Fuera de alcance de esta spec

- **Paginación de posiciones.** MVP <20 posiciones. Si futuro multi-market lo justifica, HU separada.
- **Filtros y ordenamiento** del listado (por mercado, por P&L %, alfabético). Frontend MVP solo orden alfabético por ticker o por costBasis desc (decisión menor en frontend, no merece spec).
- **Histórico de saldo / curva de equity.** HU-F18 Dashboard cubre esto a otro nivel.
- **Realized P&L** (ganancia/pérdida realizada por ventas pasadas). Requiere modelo de FIFO/LIFO sobre `app.orders` — diferido post-MVP.
- **Export CSV/PDF** del portafolio. Reporting es módulo distinto post-MVP.
- **Notificación por email de movimientos de saldo.** Solo se notifica por order events (F09/F10), no por consultas.
- **WebSocket / streaming de precios.** Mark-to-market es snapshot al request, no live. Streaming es HU separada post-MVP.
- **Comparación con benchmarks** (S&P 500, etc.). Reporting post-MVP.
- **Cancelar orden pendiente desde la sección pendientes.** Aunque la UX lo invitaría (mostrar un botón "Cancelar"), HU-F15 está fuera del MVP. La sección es solo informativa.
- **Histórico de órdenes ejecutadas / canceladas.** HU-F17 separado.

---

## 14. Preguntas abiertas

| # | Pregunta | Posible respuesta | Decisión |
|---|---|---|---|
| Q1 | ¿`marketDataAvailable` debe ser enum string `"true"|"false"|"partial"` o tripla `{ok:bool, partial:bool}`? | Enum string es más simple para frontend (switch). | Cerrada en §6.2: enum string. |
| Q2 | ¿`PendingOrderDto.quotedTotal` debe ser obligatorio o nullable? | F09 dejó `orders.quoted_total` siempre poblado al INSERT (no nulleable BD). Pero por seguridad de spec dejarlo nullable. | Cerrada en §6.2: nullable defensivamente. |
| Q3 | ¿`/positions` debe devolver `unrealizedPnL` en valor absoluto o con signo? | Con signo (negativo = pérdida). | Cerrada en §6.2: con signo, ejemplo "-17.50". |
| Q4 | ¿La pre-validación de "es trading hours" aplica a `/positions`? Si está cerrado, ¿mostramos último cierre? | No. Mark-to-market siempre usa `getLatestPrice` que devuelve la última cotización conocida; Alpaca data API maneja la semántica de "last trade" o "previous close" internamente. | Cerrada: no se pre-valida market hours en este endpoint. |
| Q5 | ¿`Auditor.audit("PORTFOLIO_VIEWED")` para analytics de engagement? | Sin valor MVP. Si compliance lo exige post-MVP, agregar. | Cerrada en §9.1: no audit. |
| Q6 | ¿Cómo manejamos el caso `quantity=0` defensivo en `app.positions`? | Por contrato F10, la fila se elimina cuando qty=0. Si por algún bug existe fila con qty=0, el listado debería filtrarla. | Repository `findByUserIdAndQuantityGreaterThan(userId, 0)` o filtrar en service. Decidir en plan (D menor). |

---

## 15. Definition of Done específica de esta spec

### Backend

- ☐ **NO se crea migración Flyway nueva** (confirmar `\d app.user_balances`, `\d app.positions`, `\d app.orders` sin alteraciones).
- ☐ `PortfolioController` nuevo con 2 endpoints (`GET /api/v1/portfolio/positions`, `GET /api/v1/portfolio/balance`) ambos con `@PreAuthorize("isAuthenticated()")` o equivalente vía SecurityFilterChain.
- ☐ 4 DTOs en `co.edu.unbosque.bloomtrade.portfolio.dto`: `PortfolioPositionsResponse`, `PositionDto`, `PendingOrderDto`, `BalanceResponse`. Todos con `@Schema` para Swagger.
- ☐ `PortfolioMapper` manual (sin MapStruct) con métodos `toPositionDto(Position, BigDecimal currentPrice)`, `toPendingOrderDto(Order)`, `toBalanceResponse(UserBalance)`, `toPositionsResponse(...)`. BigDecimal stringificado siempre.
- ☐ `OrderRepository.findByUserIdAndStatusAndAlpacaOrderIdNotNullOrderBySubmittedAtDesc(UUID userId, OrderStatus status): List<Order>` agregado con test unitario del derived query.
- ☐ `PortfolioService.getPendingOrders(userId): List<Order>` agregado.
- ☐ Fan-out a `MarketDataAdapter.getLatestPrice(ticker)` implementado con resiliencia: si falla un ticker, no propaga; si fallan todos, `marketDataAvailable=false`. Test IT con WireMock que simula 503 parcial y total.
- ☐ `MarketDataUnavailableException` capturada DENTRO del fan-out, NUNCA propagada al cliente (no 502 desde estos endpoints).
- ☐ `BalanceResponse.lastUpdatedAt` se popula desde `UserBalance.getUpdatedAt()` (la entidad ya lo tiene de F01). Si por algún caso es null (bootstrap), usar `createdAt` como fallback.
- ☐ Swagger UI muestra ambos endpoints con request/response docs y ejemplos.
- ☐ `GlobalExceptionHandler` no requiere handlers nuevos (errores existentes: 401 vía JwtAuthenticationFilter, 500 vía handler genérico).
- ☐ **NO se agregan audit events nuevos** (decisión §9.1).
- ☐ Logs INFO al entrar a cada endpoint con `userId`, position count, latency total. WARN por ticker que falla mark-to-market.

### Frontend

- ☐ Ruta nueva `/portfolio` protegida (envuelta en `<ProtectedRoute>` heredado de F02).
- ☐ `PortfolioPage` ensambla `BalanceCard + PositionsTable + PendingOrdersPanel + MarketDataBanner`.
- ☐ `usePortfolioPositions` y `useBalance` con React Query (`queryKey: ['portfolio','positions'|'balance']`).
- ☐ `portfolioApi.ts` wrappers HTTP usan el axios instance con interceptor 401 → /login.
- ☐ `types/api.ts` extendido con los 4 types nuevos.
- ☐ `messages.es.ts` con todos los copys de §12.2.
- ☐ `AppHeader` con link "Portafolio" visible solo si autenticado.
- ☐ Empty state implementado con CTA funcional a `/trade`.
- ☐ Banner condicional renderizado correctamente para los 3 estados de `marketDataAvailable`.
- ☐ Sección pending colapsable con `<details>/<summary>` o estado React; abierta por default si `count > 0`.
- ☐ Tabla responsive (desktop tabla, mobile cards).
- ☐ Botón refresh manual que llama `queryClient.invalidateQueries(['portfolio'])`.
- ☐ P&L con color + icono ▲/▼ (a11y).
- ☐ `npm run build` verde.

### Tests

- ☐ `PortfolioServiceTest` unit: ≥4 escenarios (getPendingOrders happy, getPendingOrders vacío, getPendingOrders filtra status correcto, getPendingOrders filtra alpaca_order_id NOT NULL).
- ☐ `PortfolioMapperTest` unit: ≥4 escenarios (toPositionDto con currentPrice, toPositionDto con currentPrice=null, toPendingOrderDto, toBalanceResponse).
- ☐ Test unit del fan-out / orquestador market data: happy path todos OK, todos fallan, mezcla parcial. WireMock o stub del `MarketDataAdapter`.
- ☐ `PortfolioControllerIT` integración con MockMvc + WireMock para Alpaca data API: ≥6 escenarios (AC-01, AC-02, AC-03, AC-04, AC-05, AC-06) + 2 balance (AC-21-01, AC-21-03).
- ☐ Test IT específico de aislamiento cross-user (HU-F16-AC-06 y HU-F21-AC-03 con 2 usuarios reales).
- ☐ `mvn verify` verde.

### E2E manual (HITO 5 humano)

- ☐ Login → /portfolio → ver saldo + posiciones (de demo F09/F10) + pending orders si las hay.
- ☐ Operar en /trade y volver a /portfolio → datos actualizados tras click en refresh o auto-focus.
- ☐ Desactivar Alpaca data API simulando red caída (firewall rule o `docker compose pause` del adapter en dev) → ver banner amarillo + tabla sin precios.
- ☐ Inspeccionar Network tab: ambos endpoints responden 200 OK con shapes esperados.
- ☐ Verificar que sin JWT (logout + intentar /portfolio) redirige a /login.

### Documentación / cierre

- ☐ APRENDIZAJES.md sección "Día 8 — HU-F16+F21" en primera persona.
- ☐ AGENTS.md handoff actualizado con cierre del bundle.
- ☐ Plan.md con decisiones D1–Dxx (D-MARKET-DATA-FANOUT, D-MARKET-DATA-TIMEOUT, D-PARTIAL-FAILURE-POLICY, D-PENDING-ORDERS-QUERY, D-CONTROLLER-STRUCTURE, D-FRONTEND-LAYOUT, D-PNL-COLOR-CONVENTION, D-REFRESH-MECHANISM, D-AUDIT-EVENTS) + decisiones emergentes durante implementación en §2.4 si surgen.
- ☐ Tasks.md con descomposición granular T1.x–TN.x por lote.
- ☐ Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f16-f21.txt`.
- ☐ Sin cambios a STACK.md (sin nuevas libs) ni ARCHITECTURE.md (sin módulos nuevos).

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-24 | Versión inicial del bundle | Primer SPEC post-HU-F10 con andamio completo de trading en `main` (PR #6+#7). F16+F21 son las HUs más simples del Sprint 2 (read-only, sin migración, sin transacciones complejas, sin eventos, sin emails). Bundle por afinidad: mismo módulo (Portfolio), mismo controller, misma página frontend. 8 decisiones cerradas pre-redacción (C1–C8) tras cuestionario con humano: mark-to-market con fallback elegante, 2 endpoints separados, sección pending orders incluida. 9 decisiones diferidas a `plan.md` (D-MARKET-DATA-FANOUT, D-MARKET-DATA-TIMEOUT, D-PARTIAL-FAILURE-POLICY, D-PENDING-ORDERS-QUERY, D-CONTROLLER-STRUCTURE, D-FRONTEND-LAYOUT, D-PNL-COLOR-CONVENTION, D-REFRESH-MECHANISM, D-AUDIT-EVENTS). Mitiga deudas vivas #8/#12 del handoff AGENTS.md (visibilidad de órdenes encoladas). |
