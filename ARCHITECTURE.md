# ARCHITECTURE.md — BloomTrade

**Plataforma de Day Trading | Ingeniería de Software 2 | Universidad El Bosque | 2026**

Este documento es la fuente de verdad arquitectónica del proyecto. Toda decisión de diseño tomada durante el análisis y diseño del sistema está registrada aquí. Antes de implementar cualquier funcionalidad, leer este documento completo.

---

## 1. Contexto del sistema

BloomTrade es una plataforma web de Day Trading que permite a inversionistas y comisionistas operar en cinco mercados internacionales. El sistema gestiona órdenes de compra y venta de acciones, portafolios, notificaciones en tiempo real, auditoría de operaciones y reportes ejecutivos.

**Mercados soportados:**

| Mercado | Horario de operación | Zona horaria |
|---|---|---|
| NYSE | 9:30 AM – 4:00 PM | Hora del Este (ET) |
| NASDAQ | 9:30 AM – 4:00 PM | Hora del Este (ET) |
| LSE | 8:00 AM – 4:30 PM | Greenwich (GMT) |
| TSE | 9:00 AM – 3:00 PM | Hora de Japón (JST) |
| ASX | 10:00 AM – 4:00 PM | Hora de Sídney (AEST) |

**Activos disponibles (25 en total, 5 por mercado):**

- NYSE: AAPL, MSFT, JNJ, JPM, XOM
- NASDAQ: GOOGL, AMZN, META, TSLA, NVDA
- LSE: HSBA, BP, GSK, ULVR, BARC
- TSE: 7203, 6758, 9984, 8306, 6861
- ASX: BHP, CBA, CSL, WES, WOW

**Usuarios del sistema:**

- Inversionista
- Comisionista
- Administrador
- Responsable Legal / Auditoría
- Junta Directiva

---

## 2. Estilo arquitectónico

El sistema adopta un **monolito modular** como estilo arquitectónico base. La aplicación se ejecuta como un único proceso Spring Boot que internamente está estructurado en **módulos** con fronteras explícitas y responsabilidades claramente delimitadas. Cada módulo encapsula una capacidad de negocio y se comunica con los demás únicamente a través de interfaces públicas (interfaces Java expuestas como beans Spring).

### 2.1 Justificación de la decisión

La elección sigue el principio de Bass: *escoge la arquitectura más simple que satisfaga los atributos de calidad requeridos. La distribución se añade solo cuando es necesaria, porque introduce complejidad significativa en despliegue, depuración y runtime.* Para BloomTrade los factores determinantes son:

| Factor | Implicación |
|---|---|
| Equipo de desarrollo de una persona | La complejidad operacional de servicios distribuidos no es justificable |
| Modelo de despliegue del MVP en Docker Compose local | Todos los componentes corren en la misma máquina |
| Heterogeneidad tecnológica nula (todo Java/Spring) | La ganancia clásica de microservicios no aplica |
| Escalabilidad independiente por módulo no requerida | 1500 usuarios concurrentes son alcanzables con un único proceso correctamente concurrente |
| Consistencia transaccional crítica para operaciones financieras | Una sola base de datos con transacciones ACID es la solución más simple y robusta |

### 2.2 Qué se gana con monolito modular frente a SOA distribuida

- **Consistencia transaccional natural** — operaciones que tocan múltiples módulos (ej: registro que crea User + Balance, ejecución de orden que actualiza Portfolio + Audit + Notification) ocurren en una sola transacción ACID
- **Debugging y testing simplificados** — un único stack trace, un único proceso, un único Spring context para tests de integración
- **Despliegue trivial** — un solo JAR, una sola imagen Docker
- **Sin complejidad de red interna** — no hay timeouts entre módulos, no hay retries internos, no hay service discovery
- **Disciplina arquitectónica preservada** — los módulos siguen siendo unidades de razonamiento, las interfaces siguen siendo contratos explícitos, las tácticas de Bass siguen aplicando idénticamente

### 2.3 Qué se sacrifica (y por qué es aceptable)

- **Escalabilidad independiente por módulo** — no se puede escalar `TradingService` sin escalar el monolito entero. *Aceptable porque* el MVP corre como demo local sin necesidad de escalabilidad heterogénea.
- **Despliegue independiente por módulo** — un cambio en cualquier módulo requiere redesplegar todo el JAR. *Aceptable porque* el equipo es una persona.
- **Aislamiento de fallos limitado** — un bug grave en un módulo puede afectar a otros. *Mitigado mediante* tácticas TAC-D2 (Retry) y TAC-D4 (Degradación controlada) hacia dependencias externas, encapsulación TAC-M3 estricta entre módulos, y testing exhaustivo.

### 2.4 Camino de evolución (post-MVP)

La elección de monolito modular es deliberada y no permanente. La disciplina de módulos con interfaces explícitas mantiene **abierta la puerta para extraer módulos individuales como servicios independientes** si en el futuro algún factor lo justifica (escalabilidad heterogénea, equipos múltiples, requisitos regulatorios de aislamiento). El refactor sería mecánico: el módulo extraído pasa a ser una aplicación Spring Boot separada, las interfaces Java internas se convierten en clientes HTTP, y los `IntegrationService` adapters sirven como referencia del patrón.

---

## 3. Módulos del sistema

El sistema está compuesto por **9 módulos autónomos**, cada uno implementado como un paquete Java dentro de un único proceso Spring Boot. Las dependencias entre módulos se inyectan mediante el contenedor de Spring y se resuelven en tiempo de arranque.

| Módulo | Responsabilidad | Paquete Java |
|---|---|---|
| AuthService | Autenticación MFA, gestión de sesiones, bloqueo por intentos fallidos | `co.edu.elbosque.bloomtrade.auth` |
| TradingService | Recepción, priorización, ejecución y orquestación de órdenes | `...trading` |
| PortfolioService | Gestión de posiciones, saldo y historial del inversionista | `...portfolio` |
| NotificationService | Despacho multicanal de notificaciones (SMS, Email, WhatsApp) | `...notification` |
| DashboardService | Precios en tiempo real, métricas de mercado, caché de activos | `...dashboard` |
| AdminService | Configuración de parámetros, comisiones y horarios de mercado | `...admin` |
| AuditService | Registro inmutable de eventos y trazabilidad de operaciones | `...audit` |
| IntegrationService | Adaptadores hacia Alpaca, Polygon.io, Stripe y Twilio | `...integration` |
| MonitoringService | Health checks a dependencias externas y monitoreo del proceso | `...monitoring` |

> **Nota sobre nomenclatura:** Los nombres conservan el sufijo "Service" del diseño original por continuidad con el resto de la documentación y el código. En el contexto de monolito modular debe interpretarse como **"módulo que provee servicios"** (en sentido genérico de capacidades), **no como servicio distribuido**. Esto no implica nada respecto al despliegue: todos viven dentro del mismo JAR.

---

## 4. Componentes internos por módulo

### AuthService

- `MFAValidator` — verifica el código OTP de segundo factor (vigencia 5 minutos)
- `LoginAttemptTracker` — contabiliza intentos fallidos; bloquea al tercer intento consecutivo

### TradingService

- `PriorityQueue` — cola con tres niveles de prioridad: Alta (órdenes, cancelaciones), Media (consultas de saldo y portafolio), Baja (reportes, dashboard de bajo impacto)
- `ThreadPool` — procesa múltiples órdenes en paralelo
- `OrderOrchestrator` — coordina la secuencia post-confirmación de Alpaca en orden: (1) actualizar portafolio, (2) enviar notificación, (3) registrar en auditoría

### PortfolioService

- `BalanceInitializer` — crea el balance inicial del usuario al registrarse (USD 10,000 demo); invocado desde `AuthService.RegisterService` dentro de la misma transacción

### NotificationService

- `ThreadPool` — despacha múltiples alertas simultáneamente (escenario: 300 alertas en <30s)

### DashboardService

- `PriceCache` — almacena los precios de los 25 activos; se actualiza cada 30 segundos durante horario de operación de cada mercado; persiste en Redis

### AdminService

- `ConfigurationManager` — lee y escribe parámetros de negocio en `config` schema en runtime
- `CommissionManager` — encapsula la lógica de cálculo de comisiones (consume `ConfigurationManager`)
- `MarketScheduleManager` — encapsula la lógica de validación de horarios (consume `ConfigurationManager`)

### AuditService

- `AuditLogger` — escribe eventos inmutables a ElasticSearch con: tipo de evento, identidad del actor, recurso, resultado (permitido/denegado), IP de origen, timestamp

### IntegrationService

- `AlpacaAdapter` — traduce órdenes al formato Alpaca y respuestas de Alpaca al formato interno; contiene `RetryPolicy` (3 reintentos con intervalos 1s, 3s, 5s)
- `MarketDataAdapter` — obtiene precios de Polygon.io (o Alpha Vantage como alterno) y los traduce al formato interno
- `StripeAdapter` — procesa cobros de suscripción y traduce respuestas de Stripe al formato interno
- `TwilioAdapter` — envía SMS y WhatsApp vía API de Twilio

### MonitoringService

- `HealthMonitor` — verifica periódicamente la disponibilidad de dependencias externas (Alpaca, Polygon, Stripe, Twilio, PostgreSQL, Redis, ElasticSearch) y expone métricas vía Spring Actuator

---

## 5. Interfaces entre módulos

Las interfaces entre módulos son **interfaces Java** expuestas como beans Spring. La invocación es **directa en el mismo JVM** — no hay overhead de red, no hay serialización, las llamadas son síncronas y participan naturalmente en la transacción del thread invocador.

El formato siguiente describe: **módulo que expone la interfaz** → **módulo que la consume**.

```
AuthService           EXPONE IAuthentication       CONSUME TradingService
AuthService           EXPONE IAuthentication       CONSUME PortfolioService

AuditService          EXPONE IAudit                CONSUME AuthService
AuditService          EXPONE IAudit                CONSUME TradingService
AuditService          EXPONE IAudit                CONSUME PortfolioService
AuditService          EXPONE IAudit                CONSUME AdminService

TradingService        EXPONE IOrder                CONSUME WebApp (HTTP REST)

PortfolioService      EXPONE IPortfolio            CONSUME TradingService
PortfolioService      EXPONE IBalanceInitializer   CONSUME AuthService.RegisterService

NotificationService   EXPONE INotification         CONSUME TradingService
NotificationService   EXPONE INotification         CONSUME AuthService
NotificationService   EXPONE INotification         CONSUME AdminService

AdminService          EXPONE IMarketSchedule       CONSUME TradingService
AdminService          EXPONE ICommission           CONSUME TradingService

IntegrationService    EXPONE IOrderExecution       CONSUME TradingService
IntegrationService    EXPONE IPayment              CONSUME PortfolioService
IntegrationService    EXPONE IMarketData           CONSUME DashboardService

DashboardService      EXPONE IDashboard            CONSUME WebApp (HTTP REST)

MonitoringService     EXPONE IHealthStatus         CONSUME Spring Actuator endpoint

ConfigurationManager DE AdminService  EXPONE IConfiguration  CONSUME CommissionManager DE AdminService
ConfigurationManager DE AdminService  EXPONE IConfiguration  CONSUME MarketScheduleManager DE AdminService

PriorityQueue DE TradingService  EXPONE IQueuedOrder     CONSUME ThreadPool DE TradingService
ThreadPool DE TradingService     EXPONE IProcessedOrder  CONSUME OrderOrchestrator DE TradingService
```

**Dependencias externas (consumidas por adapters):**

```
ElasticSearch         EXPONE ILogging              CONSUME AuditLogger DE AuditService
Alpaca Markets        EXPONE IAlpacaAPI            CONSUME AlpacaAdapter DE IntegrationService
Polygon.io            EXPONE IMarketDataAPI        CONSUME MarketDataAdapter DE IntegrationService
Stripe                EXPONE IStripeAPI            CONSUME StripeAdapter DE IntegrationService
Twilio                EXPONE ITwilioAPI            CONSUME TwilioAdapter DE IntegrationService
Redis                 EXPONE ICacheStore           CONSUME PriceCache DE DashboardService
MailHog/SendGrid      EXPONE ISMTP                 CONSUME NotificationService (vía Spring Mail)
```

---

## 6. Tácticas de arquitectura

Referencia: *Software Architecture in Practice*, Len Bass et al.

### 6.1 Tácticas representadas en el diagrama de componentes (intra-módulo)

| Módulo | Componente | Táctica | Atributo de calidad |
|---|---|---|---|
| AuthService | MFAValidator | Autenticar actores | Seguridad |
| AuthService | LoginAttemptTracker | Revocar acceso | Seguridad |
| TradingService | PriorityQueue | Priorizar eventos | Rendimiento |
| TradingService | ThreadPool | Introducir concurrencia | Rendimiento |
| TradingService | OrderOrchestrator | Orquestar | Interoperabilidad |
| NotificationService | ThreadPool | Introducir concurrencia | Rendimiento |
| DashboardService | PriceCache | Mantener múltiples copias de datos (Caché) | Rendimiento |
| AdminService | ConfigurationManager | Diferir el enlace mediante configuración | Modificabilidad |
| AdminService | CommissionManager | Encapsular | Modificabilidad |
| AdminService | MarketScheduleManager | Encapsular | Modificabilidad |
| AuditService | AuditLogger | Mantener registro de auditoría | Seguridad |
| IntegrationService | AlpacaAdapter | Usar un intermediario · Adaptar la interfaz | Modificabilidad · Interoperabilidad |
| IntegrationService | RetryPolicy | Retry | Disponibilidad |
| IntegrationService | MarketDataAdapter | Usar un intermediario · Adaptar la interfaz | Modificabilidad · Interoperabilidad |
| IntegrationService | StripeAdapter | Usar un intermediario · Adaptar la interfaz | Modificabilidad · Interoperabilidad |
| IntegrationService | TwilioAdapter | Usar un intermediario · Adaptar la interfaz | Modificabilidad · Interoperabilidad |
| MonitoringService | HealthMonitor | Heartbeat | Disponibilidad |

### 6.2 Tácticas representadas en el diagrama de despliegue

| Nodo / configuración | Táctica | Atributo de calidad |
|---|---|---|
| TradingService configurado con concurrencia en el JAR backend | Introducir concurrencia (TAC-R1) | Rendimiento |
| NotificationService configurado con concurrencia en el JAR backend | Introducir concurrencia (TAC-R1) | Rendimiento |
| Redis Cache como contenedor separado | Mantener múltiples copias de datos (TAC-R2) | Rendimiento |
| Schemas `config` y `app` separados en PostgreSQL | Diferir el enlace mediante configuración (TAC-M2) | Modificabilidad |
| Conexión Backend → ElasticSearch vía HTTP | Mantener registro de auditoría (TAC-S4) | Seguridad |
| MonitoringService → dependencias externas (Alpaca, Polygon, BD, etc.) vía health checks | Heartbeat (TAC-D1) | Disponibilidad |

### 6.3 Referencia completa de tácticas (16 en total)

| ID | Táctica | Categoría Bass | Atributo |
|---|---|---|---|
| TAC-D1 | Heartbeat | Detectar fallos | Disponibilidad |
| TAC-D2 | Retry | Recuperarse de fallos | Disponibilidad |
| TAC-D3 | Redundancia pasiva (Warm Standby) | Recuperarse de fallos | Disponibilidad |
| TAC-D4 | Degradación controlada | Recuperarse de fallos | Disponibilidad |
| TAC-R1 | Introducir concurrencia | Gestionar recursos | Rendimiento |
| TAC-R2 | Mantener múltiples copias de datos (Caché) | Gestionar recursos | Rendimiento |
| TAC-R3 | Priorizar eventos | Controlar la demanda | Rendimiento |
| TAC-S1 | Autenticar actores (MFA/OTP) | Resistir ataques | Seguridad |
| TAC-S2 | Autorizar actores (RBAC + lista clientes) | Resistir ataques | Seguridad |
| TAC-S3 | Revocar acceso | Reaccionar a ataques | Seguridad |
| TAC-S4 | Mantener registro de auditoría | Recuperarse de ataques | Seguridad |
| TAC-M1 | Usar un intermediario (Adapter) | Reducir acoplamiento | Modificabilidad |
| TAC-M2 | Diferir el enlace mediante configuración en BD | Diferir el enlace | Modificabilidad |
| TAC-M3 | Encapsular | Reducir acoplamiento | Modificabilidad |
| TAC-I1 | Orquestar | Gestionar interfaces | Interoperabilidad |
| TAC-I2 | Adaptar la interfaz | Gestionar interfaces | Interoperabilidad |

> **Nota sobre TAC-D3 (Warm Standby):** La táctica permanece en el catálogo arquitectónico como **disponible** pero **no se materializa en el despliegue del MVP**, que corre como una sola instancia del monolito. Para producción real, agregar una segunda instancia del JAR detrás de un load balancer cubriría TAC-D3 sin requerir cambios al diseño interno del monolito. Esta deuda se documenta como evolución post-MVP.

---

## 7. Infraestructura de despliegue

El sistema se despliega con Docker Compose como una colección de contenedores en una sola máquina:

```
┌─────────────────────────────────────────────────────────────┐
│  Cliente (browser)                                          │
│  └── WebApp React + Vite build                              │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTPS
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Contenedor: frontend (nginx)                               │
│  Sirve el build estático de React                           │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTP /api/v1/*
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Contenedor: backend (Spring Boot, un solo JAR)             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Módulos:                                            │    │
│  │  · AuthService           · AdminService             │    │
│  │  · TradingService        · AuditService             │    │
│  │  · PortfolioService      · IntegrationService       │    │
│  │  · NotificationService   · MonitoringService        │    │
│  │  · DashboardService                                 │    │
│  └─────────────────────────────────────────────────────┘    │
└────┬───────────┬─────────────┬────────────┬─────────────────┘
     │           │             │            │
     │ JDBC      │ TCP         │ HTTP       │ SMTP/HTTPS
     ▼           ▼             ▼            ▼
┌─────────┐ ┌─────────┐ ┌──────────────┐ ┌──────────────┐
│ Postgres│ │ Redis   │ │ ES + Logstash│ │ MailHog (dev)│
│         │ │         │ │  + Kibana    │ │              │
│ schemas:│ │ caché   │ │              │ │              │
│  app    │ │         │ │ logs auditor.│ │              │
│  config │ │         │ │              │ │              │
│  audit  │ │         │ │              │ │              │
└─────────┘ └─────────┘ └──────────────┘ └──────────────┘

                       │ HTTPS
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  APIs externas:                                             │
│  · Alpaca Markets (paper trading)                           │
│  · Polygon.io (market data)                                 │
│  · Stripe (suscripción premium, test mode)                  │
│  · Twilio (SMS y WhatsApp, modo trial)                      │
└─────────────────────────────────────────────────────────────┘
```

**Observaciones sobre el despliegue:**

- El backend corre como **un solo proceso JVM** con los 9 módulos internos
- No existe topología distribuida: no hay servicio de discovery, no hay balanceador interno, no hay colas de mensajes entre módulos
- La caché Redis es contenedor separado para soportar TAC-R2 (mantener múltiples copias de datos) — la caché vive fuera del proceso del backend para persistencia entre reinicios y para evitar duplicar memoria
- PostgreSQL usa **una sola instancia** con tres schemas separados (`app`, `config`, `audit`), soportando TAC-M2 a nivel de schema
- ElasticSearch + Logstash + Kibana corren como contenedores separados; el backend escribe logs estructurados JSON que Logstash recoge y envía a ES

---

## 8. APIs externas

| API | Propósito | Adaptador interno |
|---|---|---|
| Alpaca Markets | Ejecución de órdenes y gestión de fondos | AlpacaAdapter |
| Polygon.io | Datos de mercado en tiempo real | MarketDataAdapter |
| Stripe | Procesamiento de suscripción premium | StripeAdapter |
| Twilio | Envío de SMS y WhatsApp | TwilioAdapter |
| ElasticSearch | Almacenamiento de logs de auditoría | AuditLogger |
| Redis | Caché de precios de los 25 activos | PriceCache |
| MailHog (dev) / SendGrid (opcional) | Envío de emails | Spring Mail (directo) |

**Regla crítica:** Ningún módulo interno llama directamente a una API externa HTTP. Toda comunicación con APIs externas pasa por el adaptador correspondiente dentro de `IntegrationService` (TAC-M1 — Usar un intermediario). Excepción justificada: MailHog/SendGrid es protocolo SMTP estándar manejado nativamente por Spring Mail, no requiere adapter custom.

---

## 9. Reglas de negocio críticas

### Comisiones

```
Total Transacción = Precio Acción × Cantidad de Acciones
Valor Comisión = Total Transacción × Porcentaje Comisión (default: 2%, parametrizable)

Sin comisionista:
  Comisión íntegra → BloomTrade

Con comisionista (porcentajes parametrizables):
  60% → BloomTrade
  40% → Comisionista

Compra:  se descuenta (Total Transacción + Valor Comisión) del saldo
Venta:   se acredita  (Total Transacción − Valor Comisión) al saldo

Todo valor de comisión se redondea a 2 cifras decimales.
La comisión se informa al usuario ANTES de confirmar la orden.
```

### Estados de una orden

```
Pendiente → Enviada → En Ejecución → Ejecutada
                   ↘ Firmada por Comisionista →  En Ejecución
                   ↘ Cancelada
                   ↘ Rechazada
                   ↘ Expirada
                   ↘ En Revisión
                   ↘ Fallida
                   ↘ Detenida
```

El sistema debe mantener trazabilidad completa de cada cambio de estado: quién lo generó, cuándo, y bajo qué condición.

### Reglas de operación

- Para comprar: el usuario debe tener fondos suficientes (Total + Comisión)
- Para vender: el usuario debe tener al menos 1 acción del activo
- El usuario puede vender todas las acciones disponibles en su portafolio
- Toda operación genera una notificación por el canal configurado por el usuario (SMS, Email, WhatsApp)
- Toda operación queda registrada en el AuditService
- La confirmación de una compra debe procesarse en menos de 5 segundos

### Órdenes fuera de horario de mercado

Si se genera una orden fuera del horario de operación del mercado destino, la orden puede encolarse para su procesamiento en la próxima apertura. El encolamiento debe ser confirmado explícitamente por el usuario o comisionista. El sistema mantiene trazabilidad del encolamiento.

### Tipos de órdenes soportados

| Tipo | Descripción |
|---|---|
| Market Order | Compra o venta al mejor precio disponible en el mercado |
| Limit Order | Compra o venta solo si el precio alcanza un valor específico o mejor |
| Stop Loss | Venta automática si el precio cae a un nivel predeterminado |
| Take Profit | Cierre automático de posición si el precio alcanza un nivel de ganancia |

**MVP: solo Market Orders son obligatorias.** Limit Order, Stop Loss y Take Profit son Should Have.

---

## 10. Suscripción premium

- Precio: USD $12/mes o USD $120/año
- Procesamiento de pago: Stripe (vía StripeAdapter)
- Funcionalidades exclusivas de usuarios premium:
  - Alertas de precio (por encima o por debajo de umbrales definidos)
  - Watchlist (lista de observación con alertas configurables)
- La suscripción puede activarse durante el registro o posteriormente desde Gestión de Perfil

---

## 11. Autorización por rol (RBAC)

Cada solicitud incluye el rol del usuario en su token de acceso. El sistema verifica el rol antes de ejecutar cualquier acción.

| Rol | Acceso principal |
|---|---|
| Inversionista | Órdenes propias, portafolio propio, dashboard, perfil |
| Comisionista | Portafolio y órdenes de sus clientes asignados únicamente |
| Administrador | Configuración de parámetros, horarios, gestión de usuarios |
| Responsable Legal | Módulo de auditoría, reportes de cumplimiento |
| Junta Directiva | Dashboard ejecutivo, KPIs, reportes de negocio |

**Regla crítica para comisionistas:** la verificación es doble — además del rol, se valida que el inversionista objetivo está en la lista de clientes asignados a ese comisionista. Un comisionista no puede ver ni operar sobre un inversionista que no sea su cliente.

---

## 12. Estructura de logs

Cada evento registrado en ElasticSearch debe incluir:

```json
{
  "timestamp": "ISO 8601",
  "event_type": "ORDER_CREATED | ORDER_EXECUTED | LOGIN_ATTEMPT | ACCESS_DENIED | ...",
  "actor_id": "UUID del usuario",
  "actor_role": "INVESTOR | BROKER | ADMIN | LEGAL | BOARD",
  "resource": "recurso al que se intentó acceder",
  "result": "ALLOWED | DENIED",
  "ip_origin": "dirección IP",
  "session_id": "UUID de sesión",
  "order_id": "UUID de la orden (si aplica)",
  "details": { }
}
```

Los registros son inmutables. Ningún usuario, incluyendo el Administrador, puede modificarlos o eliminarlos.

---

## 13. Escenarios de calidad y sus medidas

### Disponibilidad

| ID | Escenario | Medida |
|---|---|---|
| ESC-D1 | Caída de Alpaca durante horario NYSE | Órdenes encoladas enviadas en <5 min desde restauración |
| ESC-D2 | Fallo del módulo de autenticación en apertura | Servicio restaurado en <2 min desde detección del fallo |
| ESC-D3 | Fallo total del backend con 1500 usuarios | Sistema disponible en <1 hora, sin pérdida de órdenes ni datos |

### Rendimiento

| ID | Escenario | Medida |
|---|---|---|
| ESC-R1 | 1500 órdenes simultáneas en apertura NYSE | Cada orden confirmada en <5 segundos |
| ESC-R2 | 1500 usuarios cargan dashboard simultáneamente | Dashboard carga en <2 segundos |
| ESC-R3 | Órdenes encoladas al abrir el TSE | Procesamiento inicia en <30 segundos desde apertura |
| ESC-R4 | 300 alertas de precio simultáneas (AAPL) | 300 alertas enviadas en <30 segundos |

### Seguridad

| ID | Escenario | Medida |
|---|---|---|
| ESC-S1 | 3 intentos de inicio de sesión fallidos | Cuenta bloqueada y usuario notificado en <1 minuto |
| ESC-S2 | Comisionista accede a portafolio no asignado | Acceso denegado en <1 segundo |
| ESC-S3 | Orden enviada con sesión robada | Orden rechazada en <1 segundo, fondos intactos |
| ESC-S4 | Inversionista accede a módulo de auditoría | Acceso denegado en <1 segundo |

### Modificabilidad

| ID | Escenario | Medida |
|---|---|---|
| ESC-M1 | Cambio del % de comisión desde panel admin | Activo en <5 minutos sin reiniciar el sistema |
| ESC-M2 | Actualización de horario TSE por feriado | Activo y usuarios notificados en <5 minutos |
| ESC-M3 | Reemplazo de Polygon.io por Alpha Vantage | Implementado en <2 días hábiles sin tocar DashboardService ni TradingService |

### Interoperabilidad

| ID | Escenario | Medida |
|---|---|---|
| ESC-I1 | Confirmación de ejecución recibida de Alpaca | Portafolio actualizado y notificación enviada en <5 segundos |
| ESC-I2 | Stripe rechaza el pago de suscripción premium | Mensaje de error mostrado al usuario en <3 segundos |

---

## 14. MVP obligatorio

El MVP debe implementarse en este orden de prioridad:

1. **Autenticación** — registro, inicio de sesión con MFA
2. **Gestión de Perfil** — configuración de preferencias y suscripción premium (Stripe)
3. **Trading** — Market Orders (compra y venta), notificaciones de órdenes, visualización de comisiones y estado de fondos
4. **Portafolio** — visualización de posiciones y saldo
5. **Dashboard** — comportamiento de acciones de interés del usuario con datos en tiempo real
6. **Logs** — monitoreo y trazabilidad con ElasticSearch

---

## 15. Requerimientos no funcionales clave

| RNF | Valor |
|---|---|
| Usuarios concurrentes soportados | 1500 |
| Tiempo máximo de respuesta general | 2 segundos |
| Tiempo máximo de confirmación de orden | 5 segundos |
| Disponibilidad objetivo | 99.5% mensual |
| Canales de notificación | SMS, Email, WhatsApp |
| Reintentos hacia Alpaca (RetryPolicy) | 3 intentos con intervalos 1s, 3s, 5s |
| Actualización de caché de precios | Cada 30 segundos durante horario de mercado |
| Vigencia del código OTP (MFA) | 5 minutos |
| Bloqueo de cuenta por intentos fallidos | Al tercer intento consecutivo |
| Retención de logs de auditoría | Según normativa financiera vigente |

---

## 16. Versionado de este documento

Este archivo se actualiza vía PR como cualquier código. Cada cambio significativo va con su justificación en el commit message.

### Historial de cambios

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-04 | Versión inicial con estilo SOA y 9 servicios | Cierre de fase de diseño del proyecto |
| 2.0 | 2026-05-08 | Reclasificación arquitectónica de SOA a monolito modular. Cambios principales: §2 reescrita con justificación de monolito; §3 servicios → módulos en un solo proceso JVM; §5 interfaces clarificadas como Java intra-JVM (sin red); §6 tácticas de despliegue ajustadas (warm standby diferido); §7 despliegue simplificado a un solo backend container; MonitoringService reorientado a health checks de dependencias externas. Bass: principio de simplicidad arquitectónica. | Refinamiento de clasificación para alinear con realidad de despliegue (un solo nodo según §7 original) y para hacer viable el MVP en 2 semanas con un desarrollador. |
| 2.1 | 2026-05-08 | Agregada interfaz `IBalanceInitializer` expuesta por PortfolioService y consumida por AuthService.RegisterService. Agregado componente `BalanceInitializer` en §4 PortfolioService. Renombrado proveedor de email/SMS para incluir Twilio explícitamente. | Decisión resuelta en spec HU-F01 §14 — creación del balance inicial en flujo de registro. |
