# STACK.md — BloomTrade

**Stack tecnológico del proyecto | Ingeniería de Software 2 | Universidad El Bosque | 2026**

Este documento es la fuente de verdad sobre **qué tecnologías** usa el proyecto. Toda decisión registrada aquí es vinculante: ningún componente nuevo se introduce sin actualizar primero este archivo. Si Claude Code propone una librería o herramienta no listada aquí, debe rechazarse o discutirse explícitamente antes de aceptarse.

Este archivo se complementa con:
- `ARCHITECTURE.md` — qué componentes existen y cómo se relacionan
- `CONVENTIONS.md` — cómo se escribe el código

---

## 1. Resumen ejecutivo del stack

| Capa | Tecnología | Versión | Justificación corta |
|---|---|---|---|
| Lenguaje backend | Java | 21 LTS | LTS vigente, compatible con Spring Boot 3.x |
| Framework backend | Spring Boot | 3.3.x | Madurez, ecosistema, integración con todo el stack |
| Lenguaje frontend | TypeScript | 5.x | Type safety obligatorio en proyecto financiero |
| Framework frontend | React | 18.x | Habilidad del equipo + componentes financieros disponibles |
| Build frontend | Vite | 5.x | Más rápido que CRA, soporte TS nativo |
| Base de datos | PostgreSQL | 16 | Precisión decimal nativa, JSON, schemas múltiples |
| Caché | Redis | 7.x | Requerido por arquitectura (PriceCache) |
| Logging stack | ElasticSearch + Kibana | 8.x | Auditoría centralizada inmutable |
| Mensajería de logs | Logstash | 8.x | Pipeline de ingesta hacia ES |
| API de ejecución | Alpaca Markets | v2 (paper trading) | Requerido por arquitectura |
| API de market data | Polygon.io | v3 (free tier) | Endpoint de snapshots + adapter intercambiable |
| Pasarela de pago | Stripe | 2024-x (test mode) | Requerido por arquitectura |
| SMS / WhatsApp | Twilio | API actual | Soporta ambos canales con una integración |
| Email (dev) | MailHog | 1.0.x | Captura local, sin signup, UI web en :8025 |
| Email (prod opcional) | SendGrid | API v3 | Configurable vía env, no obligatorio para MVP |
| Documentación API | OpenAPI / Swagger UI | 3.0 | Contrato como artefacto SDD |
| Containerización | Docker + Docker Compose | 24.x / v2 | Único modo de despliegue del MVP |
| CI/CD | GitHub Actions | — | Requerido por curso |
| Análisis de código | SonarCloud | — | Requerido por curso |
| Pruebas de carga | Apache JMeter | 5.x | Requerido por curso para validar RNFs |

---

## 2. Backend

### 2.1 Lenguaje y framework

**Java 21 LTS + Spring Boot 3.3.x.**

Razones de la elección sobre las otras opciones permitidas (Python, Go, .NET):
- Habilidad dominante del equipo
- Soporte nativo de tipo `BigDecimal` con semántica predecible para cálculos monetarios (regla de oro: **nunca usar `double` o `float` para dinero**)
- Spring Boot starter ecosystem cubre todas las integraciones requeridas (Web, Data JPA, Security, Validation, Actuator)
- Spring Cloud OpenFeign disponible si en el futuro se separan los servicios físicamente

### 2.2 Módulos de Spring que se van a usar

| Módulo | Para qué | Notas |
|---|---|---|
| `spring-boot-starter-web` | API REST | Servlet container Tomcat embebido |
| `spring-boot-starter-data-jpa` | ORM + repositorios | Hibernate como provider |
| `spring-boot-starter-security` | Autenticación, autorización RBAC | JWT vía filtros custom |
| `spring-boot-starter-validation` | Bean Validation (`@Valid`, `@NotNull`, etc.) | Validación de DTOs |
| `spring-boot-starter-actuator` | Health checks, metrics | Endpoints para `MonitoringService` y heartbeat |
| `spring-boot-starter-data-redis` | Cliente Redis | Para `PriceCache` y revocación JWT |
| `spring-boot-starter-mail` | Envío SMTP (email de bienvenida, MFA) | Hacia MailHog en dev (`spring.mail.host=mailhog`) |
| `spring-boot-starter-thymeleaf` | Render de plantillas de email HTML | Plantillas en `resources/templates/email/` |
| `spring-boot-starter-test` | JUnit 5 + Mockito + AssertJ | Stack de tests por defecto |

### 2.3 Librerías adicionales aprobadas

| Librería | Versión | Uso |
|---|---|---|
| `springdoc-openapi-starter-webmvc-ui` | 2.5.x | Genera Swagger UI desde anotaciones |
| `flyway-core` | 10.x | Migraciones de base de datos versionadas |
| `lombok` | 1.18.x | Reducción de boilerplate (con criterio — ver CONVENTIONS) |
| `mapstruct` | 1.5.x | Mapeo entity ↔ DTO sin reflection |
| `jjwt` (`io.jsonwebtoken`) | 0.12.x | Generación y validación de JWT |
| `resilience4j` | 2.x | Implementación de RetryPolicy (TAC-D2) |
| `logstash-logback-encoder` | 7.x | Logs en JSON para ingesta directa a ELK |
| `testcontainers` | 1.19.x | Tests de integración con PostgreSQL/Redis reales |
| `wiremock` | 3.x | Mock de Alpaca, Polygon, Stripe, Twilio en tests |
| `stripe-java` | 28.0.x | SDK oficial Stripe — Checkout Sessions, Subscriptions, Customer Portal, webhook signature verification (HU-F06) |

**Política de nuevas dependencias:** cualquier librería no listada aquí requiere actualizar este archivo en el mismo PR que la introduce.

### 2.4 Estructura de proyecto Maven

Se usa **Maven** como build tool (sobre Gradle) por familiaridad y por mejor integración con SonarCloud.

```
backend/
├── pom.xml
├── src/main/java/co/edu/unbosque/bloomtrade/
│   ├── BloomtradeApplication.java
│   ├── auth/              ← AuthService
│   ├── trading/           ← TradingService
│   ├── portfolio/         ← PortfolioService
│   ├── notification/      ← NotificationService
│   ├── dashboard/         ← DashboardService
│   ├── admin/             ← AdminService
│   ├── audit/             ← AuditService
│   ├── integration/       ← IntegrationService (adapters)
│   ├── monitoring/        ← MonitoringService
│   ├── shared/            ← DTOs comunes, excepciones, utils
│   └── config/            ← Spring config, beans, security
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-test.yml
│   └── db/migration/      ← Scripts Flyway (V1__init.sql, etc.)
└── src/test/
    ├── unit/
    └── integration/
```

Cada paquete de servicio (`auth/`, `trading/`, etc.) sigue internamente la estructura: `controller/`, `service/`, `repository/`, `domain/`, `dto/`, `mapper/`, `exception/`.

---

## 3. Frontend

### 3.1 Stack base

**React 18 + TypeScript 5 + Vite 5.**

- React 18 por concurrent rendering, hábito del equipo y soporte de librerías financieras
- TypeScript **obligatorio** (no JavaScript). En un sistema de trading, los errores de tipo son errores de plata
- Vite sobre Create React App (deprecated) por velocidad de dev server y HMR

### 3.2 Librerías aprobadas

| Librería | Versión | Uso |
|---|---|---|
| `react-router-dom` | 6.x | Enrutamiento client-side |
| `@tanstack/react-query` | 5.x | Cache de datos del servidor + polling del dashboard |
| `axios` | 1.x | Cliente HTTP (sobre fetch por interceptors) |
| `react-hook-form` + `zod` | 7.x / 3.x | Formularios + validación tipada |
| `@hookform/resolvers` | 3.x | Adaptador zod ↔ react-hook-form |
| `tailwindcss` | 3.x | Estilos utility-first |
| `shadcn/ui` | latest | Componentes accesibles sobre Radix + Tailwind |
| `recharts` | 2.x | Gráficos para Dashboard de acciones |
| `lucide-react` | latest | Iconografía |
| `date-fns` | 3.x | Manipulación de fechas (timezones de mercados) |
| `vitest` + `@testing-library/react` | latest | Tests de componentes |

**Política de nuevas dependencias:** misma regla que el backend.

### 3.3 Estructura de proyecto

```
frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── pages/            ← Una página por ruta principal
│   ├── features/         ← Módulos por dominio (auth, trading, portfolio, ...)
│   ├── components/       ← Componentes compartidos
│   ├── hooks/            ← Custom hooks
│   ├── lib/              ← Cliente axios, utilidades
│   ├── types/            ← Types compartidos (idealmente generados desde OpenAPI)
│   └── styles/
└── tests/
```

### 3.4 Generación de tipos desde OpenAPI

El backend expone `/v3/api-docs`. Se usa **`openapi-typescript`** para generar tipos TS desde ese contrato. Esto cierra el loop SDD: la spec OpenAPI es ella misma fuente de verdad para los tipos del frontend.

```bash
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/types/api.ts
```

Este comando se ejecuta como script npm (`npm run gen:api`) y se corre antes de cada build.

---

## 4. Base de datos

### 4.1 Motor

**PostgreSQL 16.** Mismo motor para `AppDatabase` y `ConfigDatabase`, **schemas separados** dentro de la misma instancia:

```sql
CREATE SCHEMA app;       -- Datos transaccionales del negocio
CREATE SCHEMA config;    -- Parámetros configurables por Admin (TAC-M2)
CREATE SCHEMA audit;     -- Eventos espejo locales (la fuente real es ES)
```

Razones:
- Una sola instancia simplifica el `docker-compose.yml`
- Schemas separados respetan la separación arquitectónica de `ARCHITECTURE.md` §7
- Permisos a nivel schema permiten que el `AdminService` tenga acceso de escritura a `config` pero solo lectura a `app`

### 4.2 Tipos numéricos para dinero

**Regla inviolable:** todo monto de dinero, precio de acción, comisión, porcentaje y cantidad de acciones se modela con `NUMERIC(precision, scale)` en PostgreSQL y `BigDecimal` en Java. **Nunca `DOUBLE PRECISION`, `REAL`, `double` o `float`.**

| Concepto | Tipo SQL | Tipo Java |
|---|---|---|
| Precio de acción | `NUMERIC(19, 4)` | `BigDecimal` |
| Cantidad de acciones | `NUMERIC(19, 4)` | `BigDecimal` (puede haber fraccionarias) |
| Saldo del usuario | `NUMERIC(19, 2)` | `BigDecimal` |
| Comisión | `NUMERIC(19, 2)` | `BigDecimal` |
| Porcentaje (0-100) | `NUMERIC(5, 2)` | `BigDecimal` |

### 4.3 Migraciones

**Flyway**, ubicadas en `backend/src/main/resources/db/migration/`. Convención: `V{n}__{descripcion_snake_case}.sql`. Ejemplos:
- `V1__create_schemas.sql`
- `V2__auth_users_table.sql`
- `V3__trading_orders_table.sql`

Una migración nunca se modifica después de mergeada — siempre se crea una nueva. Esto es no negociable.

### 4.4 Acceso a datos

**Spring Data JPA** con interfaces `Repository`. Para queries complejas (reportes, dashboard ejecutivo) se usa **JPQL** o **native queries**. **Prohibido** concatenar SQL como string — siempre parámetros nombrados.

---

## 5. Caché

**Redis 7.x.** Usos en el MVP:

| Uso | Key pattern | TTL | Componente |
|---|---|---|---|
| Cache de precios | `price:{ticker}` | 30s | `PriceCache` (DashboardService) |
| Códigos OTP | `otp:{userId}` | 5 min | `MFAValidator` (AuthService) |
| Tokens JWT revocados | `revoked:{jti}` | hasta exp del token | `AuthService` |
| Rate limiting login | `login:attempts:{userId}` | 1 hora | `LoginAttemptTracker` |

Cliente: **Spring Data Redis** con serializer **JSON**.

---

## 6. Observabilidad — ELK Stack

### 6.1 Componentes

- **ElasticSearch 8.x** — almacenamiento de logs de auditoría
- **Logstash 8.x** — pipeline de ingesta desde la app
- **Kibana 8.x** — UI de búsqueda y dashboards

Todos corren como contenedores en el `docker-compose.yml`.

### 6.2 Modo de envío de logs

La aplicación Spring Boot escribe logs en **JSON estructurado** (vía `logstash-logback-encoder`) hacia stdout. Logstash los recoge desde el archivo o vía pipe Docker. Esto evita acoplar la app al transporte.

### 6.3 Estructura de log de auditoría

Definida en `ARCHITECTURE.md` §12. **Ningún log de auditoría puede omitir ninguno de esos campos.** El backend define una clase `AuditEvent` que valida la estructura antes de emitir.

### 6.4 Índices

| Índice | Contenido | Retención inicial |
|---|---|---|
| `audit-events-{YYYY.MM}` | Eventos de auditoría inmutables | 90 días en demo |
| `app-logs-{YYYY.MM}` | Logs operacionales (info, warn, error) | 30 días en demo |

---

## 7. Servicios externos

### 7.1 Alpaca Markets (ejecución de órdenes)

- **Modo:** Paper Trading (sandbox)
- **Endpoint base:** `https://paper-api.alpaca.markets`
- **Autenticación:** API Key + Secret en variables de entorno
- **Adapter:** `AlpacaAdapter` en `IntegrationService`
- **RetryPolicy:** Resilience4j configurado con 3 reintentos a 1s, 3s, 5s (TAC-D2)
- **Cuenta de servicio:** una sola cuenta de paper trading compartida en demo (no creación por usuario en MVP)

> **Decisión simplificadora del MVP:** el PDF original sugiere crear cuenta Alpaca por usuario. Para el MVP local con Docker Compose se trabaja contra una única cuenta paper de demostración. La arquitectura no se ve afectada — el `AlpacaAdapter` simplemente usa credenciales únicas. Este simplificación se documenta también en el ROADMAP.

### 7.2 Polygon.io (datos de mercado)

- **Plan:** Free tier
- **Endpoint base:** `https://api.polygon.io`
- **Autenticación:** API Key en query param `apiKey={key}` o header
- **Adapter:** `MarketDataAdapter` en `IntegrationService`
- **Estrategia de fetch:** endpoint `/v2/snapshot/locale/us/markets/stocks/tickers?tickers={list}` permite traer múltiples tickers en una llamada
- **Limitación del free tier:** 5 req/min, datos end-of-day. Para el MVP **es suficiente** porque el caché Redis amortigua y la demo no requiere precios reales en tiempo real
- **Plan de migración:** si se necesita real-time, se cambia a tier pago o se reemplaza por Alpaca Market Data sin tocar `DashboardService` (TAC-M1)

### 7.3 Stripe (suscripción premium)

- **Modo:** Test mode
- **SDK:** `stripe-java`
- **Adapter:** `StripeAdapter` en `IntegrationService`
- **Productos:** dos productos pre-creados en Stripe test dashboard:
  - `premium_monthly` — USD $12/mes
  - `premium_yearly` — USD $120/año
- **Tarjetas de prueba:** las oficiales de Stripe (`4242 4242 4242 4242` éxito, etc.)
- **Webhooks:** se manejan en endpoint `/api/v1/webhooks/stripe` con verificación de firma

### 7.4 Twilio (SMS y WhatsApp)

- **SDK:** `twilio-java`
- **Adapter:** `TwilioAdapter` (componente nuevo dentro de `NotificationService`)
- **Modo dev:** Twilio test credentials (no envía mensajes reales, simula respuestas)
- **Canales:**
  - SMS vía `Messaging` API
  - WhatsApp vía `WhatsApp Sandbox` de Twilio

### 7.5 Email — MailHog (dev) / SendGrid (opcional)

- **Dev:** `mailhog/mailhog:latest` en Docker Compose, puerto SMTP 1025, UI en 8025
- **Spring config:** `spring.mail.host=mailhog`, `spring.mail.port=1025`
- **Switch a SendGrid:** se controla por variable `EMAIL_PROVIDER=mailhog|sendgrid`. Si es `sendgrid`, se usa `spring-sendgrid` con `SENDGRID_API_KEY`

---

## 8. Autenticación y autorización

### 8.1 Esquema

**JWT stateless con refresh tokens.**

| Token | Vida | Almacenamiento cliente | Revocación |
|---|---|---|---|
| Access Token | 15 min | Memoria (no localStorage) | Lista negra en Redis por `jti` |
| Refresh Token | 7 días | HttpOnly Secure SameSite=Strict cookie | Rotación en cada refresh + revocación en Redis |

Algoritmo: **HS256** con secret en variable de entorno (no en código, no en repositorio).

### 8.2 MFA

- **Método:** OTP por email
- **Largo del código:** 6 dígitos
- **Vigencia:** 5 minutos (alineado con `ARCHITECTURE.md`)
- **Almacenamiento:** Redis con TTL
- **Bloqueo:** 3 intentos fallidos consecutivos bloquean la cuenta (TAC-S3)

### 8.3 RBAC

Implementado vía Spring Security `@PreAuthorize` con expresiones SpEL. Roles: `INVESTOR`, `BROKER`, `ADMIN`, `LEGAL`, `BOARD`. La verificación adicional para BROKER (lista de clientes asignados) se hace en una capa custom — ver `ARCHITECTURE.md` §11.

---

## 9. API REST

### 9.1 Convenciones generales

- **Base path:** `/api/v1`
- **Versionado:** en URL (`/v1/`, `/v2/`)
- **Formato:** JSON únicamente, `Content-Type: application/json`
- **Charset:** UTF-8 obligatorio
- **Timestamps:** ISO 8601 con timezone (`2026-05-07T14:32:18.123Z`)

### 9.2 Códigos HTTP

| Código | Cuándo usarlo |
|---|---|
| 200 OK | Lectura exitosa |
| 201 Created | Creación exitosa con recurso devuelto |
| 204 No Content | Operación exitosa sin payload |
| 400 Bad Request | Validación de entrada falló |
| 401 Unauthorized | Sin credenciales o credenciales inválidas |
| 403 Forbidden | Autenticado pero sin permisos |
| 404 Not Found | Recurso inexistente |
| 409 Conflict | Estado actual incompatible (ej: cancelar orden ya ejecutada) |
| 422 Unprocessable Entity | Regla de negocio violada (ej: fondos insuficientes) |
| 429 Too Many Requests | Rate limiting |
| 500 Internal Server Error | Error inesperado |
| 503 Service Unavailable | Dependencia externa caída tras retries |

### 9.3 Formato estándar de error

```json
{
  "timestamp": "2026-05-07T14:32:18.123Z",
  "status": 422,
  "error": "INSUFFICIENT_FUNDS",
  "message": "El saldo disponible es insuficiente para completar la operación",
  "path": "/api/v1/orders",
  "traceId": "uuid-del-request"
}
```

### 9.4 Documentación

Swagger UI expuesto en `/swagger-ui.html`. Spec OpenAPI accesible en `/v3/api-docs`. **Todo controller debe tener anotaciones `@Operation`, `@Parameter`, `@ApiResponse`** — un endpoint sin documentar es un endpoint roto.

---

## 10. Containerización y despliegue

### 10.1 Estrategia

**Docker Compose únicamente para el MVP.** Sin Kubernetes, sin nube, sin orquestación adicional. La arquitectura del `ARCHITECTURE.md` (servidor principal, respaldo, BD, Redis, etc.) se traduce a contenedores en una sola red Docker.

### 10.2 Servicios del compose

```
backend         (Spring Boot)
frontend        (nginx servirá el build estático de React)
postgres        (postgres:16-alpine)
redis           (redis:7-alpine)
elasticsearch   (elasticsearch:8.x)
logstash        (logstash:8.x)
kibana          (kibana:8.x)
mailhog         (mailhog/mailhog)
```

### 10.3 Variables de entorno

Manejo vía archivo **`.env`** en la raíz (NO commiteado), con un **`.env.example`** que sí se commitea. Spring lee variables vía `@Value` o `application.yml` con substitución `${VAR}`.

Variables obligatorias:
```
POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
REDIS_PASSWORD
JWT_SECRET (mínimo 256 bits)
ALPACA_API_KEY, ALPACA_API_SECRET
POLYGON_API_KEY
STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET
TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER
EMAIL_PROVIDER (mailhog|sendgrid)
SENDGRID_API_KEY (solo si provider=sendgrid)
```

### 10.4 Profiles de Spring

- `dev` — desarrollo local con hot reload, logs verbose
- `test` — para tests automatizados, usa Testcontainers
- `prod` — para el "demo" final, logs JSON, sin DevTools

---

## 11. CI/CD

### 11.1 GitHub Actions

Pipeline definido en `.github/workflows/ci.yml`. Jobs:

1. **`backend-build-test`** — `mvn verify` con caché de dependencias
2. **`frontend-build-test`** — `npm ci && npm run lint && npm run test && npm run build`
3. **`sonar-scan`** — análisis SonarCloud (depende de jobs 1 y 2 para enviar coverage)
4. **`docker-build`** — solo en push a `main`, construye imágenes (no las publica en MVP)

**El pipeline verde es bloqueante para mergear PRs** (configurado en branch protection de `main`).

### 11.2 SonarCloud

- Proyecto creado en sonarcloud.io con organización del equipo
- Quality Gate: el por defecto de SonarCloud (puede afinarse después)
- Coverage objetivo: ver `CONVENTIONS.md` §Tests

---

## 12. Pruebas de carga — JMeter

### 12.1 Escenarios mínimos a cubrir

Mapeados a los escenarios de calidad de `ARCHITECTURE.md` §13:

| Escenario calidad | Plan JMeter | Threads | Ramp-up | Métrica esperada |
|---|---|---|---|---|
| ESC-R1 | `loadtest_orders.jmx` | 1500 | 60s | <5s p95 confirmación |
| ESC-R2 | `loadtest_dashboard.jmx` | 1500 | 60s | <2s p95 carga |
| ESC-R4 | `loadtest_alerts.jmx` | 300 alertas | 0s | <30s todas enviadas |

Los `.jmx` viven en `/load-tests/` en el repo.

### 12.2 Restricción

**Stripe NO se incluye en pruebas JMeter** (instrucción explícita del PDF del proyecto). Se mockea o se excluye del flujo de carga.

---

## 13. Decisiones diferidas (no MVP)

Estas decisiones se documentan aquí para que cuando aparezcan no haya improvisación:

| Decisión | Cuándo retomarla |
|---|---|
| Autenticación con TOTP / Authenticator | Post-MVP, si hay tiempo |
| Sustitución de Polygon por Alpaca Market Data | Si free tier de Polygon se queda corto en demo |
| Migración de Docker Compose a Kubernetes | Fuera del alcance del curso |
| WebSockets para precios real-time | Post-MVP |
| Internacionalización (i18n) | Post-MVP — todo en español por ahora |
| Mobile native | Fuera del alcance del curso |

---

## 14. Versionado de este documento

Este archivo se actualiza vía PR como cualquier código. Cada cambio significativo va con su justificación en el commit message. Si una decisión cambia (ej: se reemplaza Polygon por Alpha Vantage), se **edita la decisión vigente** y se agrega una entrada al historial al final.

### Historial de cambios

| Fecha | Cambio | Razón |
|---|---|---|
| 2026-05-07 | Versión inicial | Cierre de fase de diseño, inicio de implementación SDD |
| 2026-05-19 | §2.2: + `spring-boot-starter-mail`, `spring-boot-starter-thymeleaf`. §3.2: + `@hookform/resolvers`. | Requeridas por HU-F01: email de bienvenida vía MailHog con plantilla Thymeleaf, y resolver zod en el formulario de registro. Aprobadas por el humano. refs specs/HU-F01-registrarse/SPEC.md |
