# ROADMAP.md — BloomTrade

**Plan de entrega del MVP | Ingeniería de Software 2 | Universidad El Bosque | 2026**

Este documento es el contrato de alcance del proyecto. Define **qué se entrega**, **qué no se entrega**, y **cuándo**. Cualquier desviación sobre lo registrado aquí debe documentarse en el mismo PR que la introduce, con justificación.

Complementa a `ARCHITECTURE.md` (qué se construye), `STACK.md` (con qué) y `CONVENTIONS.md` (cómo).

---

## 1. Estado actual y contexto

| Variable | Valor |
|---|---|
| Plan original | 5 sprints de 1 semana cada uno |
| Sprints ya transcurridos sin entregable | 3 |
| Tiempo efectivo restante | **2 semanas** |
| Tamaño del equipo | **1 desarrollador** |
| Modalidad de despliegue | Local con Docker Compose |
| Modalidad de desarrollo | SDD asistido con Claude Code |

### Implicación crítica

El plan original de 5 sprints contemplaba 4 módulos principales (Sprints 1–4) que cubrían la totalidad de las HUs del backlog. Con 2 semanas y un solo desarrollador, **solo es realista entregar el contenido de Sprint 1 + Sprint 2** ajustado al MVP estricto definido en el PDF del proyecto. Los Sprints 3 y 4 originales se trasladan al backlog post-MVP de forma documentada.

Esto **no es un fracaso de planeación** — es la consecuencia honesta de los retrasos previos. Documentarlo explícitamente con justificación es preferible a llegar a la entrega final con todo a medias.

---

## 2. Alcance del MVP — Lo que SÍ se entrega

Se entrega un sistema funcional, demostrable con `docker-compose up`, que cubre:

### 2.1 Funcionalidad

| Módulo | Historias incluidas | Criterio de éxito |
|---|---|---|
| Autenticación | HU-F01 Registrarse, HU-F02 Iniciar sesión, HU-F03 Verificar código MFA | Usuario puede registrarse, recibir código MFA por email vía MailHog, e iniciar sesión |
| Perfil | HU-F04 Configurar perfil (versión mínima), HU-F20 Configurar canal de notificación | Usuario puede actualizar datos básicos y elegir canal de notificación |
| Suscripción | HU-F06 Suscribirse a plan premium | Usuario puede pagar suscripción mensual o anual con Stripe en modo test |
| Trading | HU-F09 Orden compra Market, HU-F10 Orden venta Market | Usuario puede comprar y vender acciones; comisión calculada y mostrada antes de confirmar; orden ejecutada vía Alpaca paper |
| Portafolio | HU-F16 Consultar portafolio, HU-F21 Consultar saldo | Usuario ve sus posiciones actuales y saldo disponible |
| Dashboard | HU-F18 Dashboard de acciones | Usuario ve precios actualizados (polling 30s) de los 25 activos definidos |
| Observabilidad | (infraestructura) | Toda operación de trading, login y cambio de perfil emite log estructurado a ElasticSearch; consultable desde Kibana |

### 2.2 Calidad

- Tests unitarios y de integración con cobertura según `CONVENTIONS.md` §7.1
- Pipeline GitHub Actions verde con SonarCloud sin issues bloqueantes
- Pruebas de carga JMeter ejecutadas para los escenarios ESC-R1 y ESC-R2 de `ARCHITECTURE.md` §13
- Documentación API completa en Swagger UI

### 2.3 Documentación entregable

- `ARCHITECTURE.md`, `STACK.md`, `CONVENTIONS.md`, `ROADMAP.md`, `README.md`
- Specs SDD de cada HU implementada en `specs/HU-FXX-slug/`
- Bitácora de prompts a Claude Code en `docs/prompts/sprint-X.md`
- Diagramas C4 y de despliegue actualizados en `docs/`
- Diagrama de secuencia para envío y ejecución de orden (exigido por PDF)

---

## 3. Recortes explícitos respecto al plan original de Jira

### 3.1 Removidos del MVP — irán al backlog post-MVP

| HU | Razón del recorte |
|---|---|
| HU-F07 Verificar correo en registro | Con MailHog en local, el correo de bienvenida no aporta valor demostrable. Se hace auto-verify en el MVP. |
| HU-F08 Recuperar contraseña | Flujo importante pero no parte del MVP estricto del PDF. |
| HU-F14 Encolar orden | Asume que el mercado destino está cerrado. Para demo local se asume "mercados siempre abiertos". El `MarketScheduleManager` queda implementado pero el flujo de encolamiento queda diferido. |
| HU-F15 Cancelar orden | Stretch goal — se intenta si el Sprint 2 va adelantado. |
| HU-F34 Auditar transacciones (UI Legal) | La **infraestructura** de auditoría sí se entrega (logs en ES, consultables en Kibana). La **UI dedicada** para usuario Legal se difiere — Kibana cumple el rol de herramienta de auditoría en el MVP. |

### 3.2 Fuera de alcance — todo el Sprint 3 original

Trasladado al backlog post-MVP completo. Esto incluye Limit/Stop Loss/Take Profit, Watchlist, alertas de precio, propuestas de inversión, funcionalidades de Comisionista y Recuperar contraseña. Justificación: son **Should Have** según el `ARCHITECTURE.md` §9, no Must Have.

### 3.3 Fuera de alcance — todo el Sprint 4 original

Trasladado al backlog post-MVP completo. Esto incluye módulo de Administrador (gestión de usuarios, configuración de horarios y comisiones), reportes empresariales, dashboard ejecutivo de Junta Directiva, restricción de operaciones por Legal. Justificación: roles de usuario fuera del MVP funcional definido en el PDF.

### 3.4 Reglas de promoción al MVP

Si el desarrollo va **adelantado** al cierre del Día 9, las siguientes historias se "promueven" en orden estricto:

1. HU-F15 Cancelar orden
2. HU-F08 Recuperar contraseña
3. HU-F23 Consultar historial de órdenes (HU-F17)

Si el desarrollo va **retrasado** al cierre del Día 5, los recortes adicionales se aplican en orden:

1. Cortar HU-F20 Configurar canal de notificación → default a email
2. Cortar HU-F04 Configurar perfil → permitir solo cambio de password y nada más
3. Renegociar el alcance del dashboard → 5 acciones fijas en lugar de 25

---

## 4. Cronograma — Semana 1 (Sprint 1: Auth + Profile + Subscription)

> **Nota:** Día N significa día calendario contado desde el inicio del trabajo. Cada "día" asume aproximadamente 6–8 horas de trabajo efectivo. Las cifras de duración por feature suponen uso intensivo de Claude Code con specs completas.

### Día 0 — Bootstrap del proyecto (1 día completo)

**Objetivo:** que `docker-compose up` levante todo el stack vacío y funcional, con CI/CD operando.

Tareas:
- Crear repositorio en GitHub con estructura de `CONVENTIONS.md` §1
- Crear `docker-compose.yml` con: postgres, redis, elasticsearch, logstash, kibana, mailhog
- Bootstrap backend Spring Boot con dependencias de `STACK.md` §2.2
- Bootstrap frontend Vite + React + TypeScript con dependencias de `STACK.md` §3.2
- Migración Flyway `V1__create_schemas.sql` que crea schemas `app`, `config`, `audit`
- Configurar `application.yml` con perfiles `dev`, `test`, `prod`
- Configurar Spring Security con filtros JWT skeleton
- Configurar SpringDoc OpenAPI generando Swagger UI
- Configurar `logstash-logback-encoder` para emitir JSON
- Configurar pipeline `.github/workflows/ci.yml` con jobs de build, test, sonar
- Configurar SonarCloud y vincular al repo
- Crear branch protection en `main` con status checks bloqueantes
- Escribir `README.md` con instrucciones de setup local
- Primer commit en `main`: `chore(infra): bootstrap del proyecto con stack completo`

**Definition of Done del Día 0:**
- ☐ `docker-compose up` levanta los 8 contenedores sin error
- ☐ Backend responde 200 en `/actuator/health`
- ☐ Frontend sirve la página inicial en `localhost:5173`
- ☐ Swagger UI accesible en `localhost:8080/swagger-ui.html`
- ☐ Kibana accesible en `localhost:5601`
- ☐ MailHog UI accesible en `localhost:8025`
- ☐ Pipeline en GitHub Actions verde
- ☐ SonarCloud reportando coverage 0% sin errores

### Día 1 — HU-F01 Registrarse

Spec → Plan → Tasks → Implementación. Resultado: usuario puede crearse con email, password, nombre. Password hasheado con BCrypt. Auto-verify (sin envío de correo de verificación). Log de auditoría `USER_REGISTERED` emitido.

### Día 2 — HU-F02 Iniciar sesión + HU-F03 Verificar código MFA (bundle)

Estas dos HUs forman un solo flujo lógico de autenticación y se implementan en conjunto. Resultado: usuario ingresa credenciales, recibe email con código MFA de 6 dígitos vía MailHog, ingresa código y recibe par de tokens JWT (access + refresh). Bloqueo al tercer intento fallido. Logs `LOGIN_ATTEMPT` (allowed/denied) y `MFA_VERIFIED` emitidos.

### Día 3 — HU-F04 Configurar perfil + HU-F20 Configurar notificaciones (bundle)

Resultado: usuario autenticado puede ver y actualizar datos básicos de perfil, y elegir canal de notificación (email, SMS, WhatsApp). Para el MVP el canal por defecto es email. Twilio queda configurado pero solo se usa si el usuario elige SMS/WhatsApp explícitamente. Log `PROFILE_UPDATED` emitido.

### Día 4 — HU-F06 Suscribirse a plan premium

Resultado: usuario puede iniciar checkout de Stripe (Checkout Session), seleccionar plan mensual o anual, completar pago con tarjeta de prueba `4242 4242 4242 4242`, y recibir confirmación. Webhook de Stripe procesado correctamente. Log `SUBSCRIPTION_ACTIVATED` emitido. Estado del usuario cambia a `PREMIUM`.

### Día 5 — Buffer + Sprint 1 Review

- Estabilización: arreglar bugs encontrados, completar tests faltantes para llegar a coverage objetivo
- Validar manualmente el flujo end-to-end completo: registrarse → login + MFA → configurar perfil → suscribirse premium
- Documentar Sprint 1 Review en `docs/sprints/sprint-1-review.md`: qué se entregó, qué quedó fuera, lecciones
- Sprint 1 Retro en `docs/sprints/sprint-1-retro.md`: qué cambiar para Sprint 2
- Refinar specs de Sprint 2 con aprendizajes de Sprint 1
- **Checkpoint de scope:** decidir si aplican recortes adicionales del §3.4 o si hay margen para promociones

---

## 5. Cronograma — Semana 2 (Sprint 2: Trading + Portfolio + Dashboard)

### Día 6 — HU-F09 Orden de compra Market

Resultado: usuario premium o regular puede colocar orden de compra Market sobre los 25 activos disponibles. Sistema valida fondos suficientes (Total + Comisión). Comisión calculada y mostrada **antes** de confirmar. Orden enviada a Alpaca paper, ejecutada, portafolio y saldo actualizados. Notificación email enviada. Logs `ORDER_CREATED`, `ORDER_EXECUTED` emitidos.

Esta es la HU **más compleja** del MVP — toca `TradingService`, `PortfolioService`, `IntegrationService` (AlpacaAdapter), `NotificationService`, `AuditService`, `AdminService` (CommissionManager). Requiere spec especialmente cuidadosa.

### Día 7 — HU-F10 Orden de venta Market (+ HU-F15 Cancelar si hay tiempo)

Resultado: usuario puede vender acciones de su portafolio. Sistema valida tenencia ≥ cantidad solicitada. Comisión descontada del producto. Logs equivalentes emitidos.

Si el Día 6 cerró sin desbordar, también se hace HU-F15 Cancelar orden — pero solo aplica para órdenes en estado `Pending` (con Alpaca paper la mayoría se ejecutan inmediato, así que Cancelar es relevante principalmente para cuando se rechace fondos o haya errores de mercado).

### Día 8 — HU-F16 Consultar portafolio + HU-F21 Consultar saldo (bundle)

Resultado: usuario ve sus posiciones actuales (ticker, cantidad, precio promedio compra, precio actual, valor total, ganancia/pérdida en %) y saldo disponible. UI con tabla y resumen. Filtro por ticker.

### Día 9 — HU-F18 Dashboard de acciones

Resultado: usuario ve un dashboard con los 25 activos disponibles, precio actual, variación del día. Refresco vía polling cada 30s desde frontend usando React Query. Backend implementa `PriceCache` con Redis: si el caché tiene un valor < 30s lo devuelve, si no consulta a Polygon vía `MarketDataAdapter`. Frontend usa `recharts` para gráficas simples.

### Día 10 — Estabilización + Pruebas de carga + Demo + Documentación final

- Ejecutar planes JMeter para ESC-R1 (1500 órdenes simultáneas) y ESC-R2 (1500 dashboards). Documentar resultados en `docs/load-tests/results.md`.
- Validar manualmente flujo end-to-end completo del MVP
- Generar/actualizar diagramas C4 y de despliegue (no implementación inicial — refresh con la realidad del código)
- Generar diagrama de secuencia para "envío y ejecución de orden" exigido por PDF
- Sprint 2 Review en `docs/sprints/sprint-2-review.md`
- Sprint 2 Retro en `docs/sprints/sprint-2-retro.md`
- Grabación de demo del MVP completo
- Cierre de Informe Final con secciones exigidas por PDF
- Verificación final: branch protection, todos los PRs squash-merged, bitácora de prompts completa

---

## 6. Definition of Done del MVP completo

El MVP se considera entregable cuando todas estas casillas están marcadas:

### Funcional
- ☐ `docker-compose up` levanta el stack completo sin error en menos de 5 minutos
- ☐ Las 9 HUs del MVP (§2.1) están implementadas, testeadas y demostrables
- ☐ Flujo end-to-end demostrado: registro → login + MFA → suscripción → compra → portafolio → dashboard

### Calidad
- ☐ Cobertura de tests cumple objetivos de `CONVENTIONS.md` §7.1
- ☐ Pipeline GitHub Actions en verde sobre `main`
- ☐ SonarCloud Quality Gate pasando
- ☐ Pruebas JMeter ejecutadas para ESC-R1 y ESC-R2 con resultados documentados

### Trazabilidad SDD
- ☐ Cada HU del MVP tiene su `specs/HU-FXX-slug/spec.md` versionado en `main`
- ☐ Cada HU tiene su `plan.md` y `tasks.md` versionados
- ☐ Bitácora de prompts a Claude Code completa para ambos sprints
- ☐ Commits asistidos llevan trailer `Co-authored-by: Claude` consistente
- ☐ Toda HU mergeada referencia su spec y su número de HU en commit messages

### Documentación
- ☐ `ARCHITECTURE.md`, `STACK.md`, `CONVENTIONS.md`, `ROADMAP.md`, `README.md` vigentes y consistentes con el código
- ☐ Diagramas C4, despliegue y secuencia de orden actualizados
- ☐ Sprint Reviews y Retros documentados
- ☐ Informe Final con todas las secciones del PDF

---

## 7. Riesgos y planes de contingencia

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Polygon free tier insuficiente para demo | Media | Bajo | Cache Redis amortigua; alternativa documentada (Alpaca Market Data) en `STACK.md` §13 |
| Stripe webhook complica integración | Media | Medio | Usar Stripe CLI para forwarding local; tests con WireMock para flujo offline |
| ELK consume demasiada RAM en local | Alta | Medio | Configurar ES con `-Xms512m -Xmx512m`; documentar mínimos en `README.md` |
| Atraso en Sprint 1 → MVP no entregable | Media | Alto | Aplicar recortes del §3.4 al cierre del Día 5 sin discusión |
| Atraso en Sprint 2 → Trading incompleto | Media | Alto | Trading es el corazón del producto: si hay que cortar algo en Sprint 2, se corta dashboard antes que portafolio o trading |
| Bug crítico en Alpaca paper integration | Baja | Alto | El `AlpacaAdapter` tiene `RetryPolicy`; en último caso se usa stub que simula respuestas para la demo |
| Bloqueo por desconocimiento de JMeter | Alta | Bajo | El usuario lo declaró desconocido — invertir 1–2 horas previas con tutoriales antes del Día 10 |
| Bloqueo por desconocimiento de GitHub | Media | Medio | Branch protection y workflows requieren atención; resolver en el Día 0 con apoyo de Claude Code |

### 7.1 Regla maestra de contingencia

**Si al cierre de cualquier día N el alcance acumulado del día está incompleto, se aplica recorte antes de empezar el día N+1**, no se arrastra. Arrastrar deuda funcional es la causa probada de no entregar.

---

## 8. Backlog post-MVP — fuera de alcance del entregable

Las siguientes HUs están especificadas en `Historias_de_Usuario.docx` y `ARCHITECTURE.md` pero **explícitamente fuera del entregable del curso**. Quedan documentadas para evidenciar análisis completo.

### 8.1 Originalmente Sprint 3

- HU-F08 Recuperar contraseña
- HU-F05 Seleccionar comisionista
- HU-F11 Limit Order
- HU-F12 Stop Loss
- HU-F13 Take Profit
- HU-F17 Consultar historial de órdenes
- HU-F19 Configurar alertas de precio (premium)
- HU-F23 Configurar Watchlist (premium)
- HU-F22 Aprobar propuesta de inversión
- HU-F24 Consultar portafolio de usuarios (rol Comisionista)
- HU-F25 Consultar historial de órdenes de usuarios (rol Comisionista)

### 8.2 Originalmente Sprint 4

- HU-F26 Consultar perfil de usuario (rol Comisionista)
- HU-F27 Crear propuesta de inversión (rol Comisionista)
- HU-F28 Firmar orden (rol Comisionista)
- HU-F29 Configurar horarios de mercado (rol Administrador)
- HU-F30 Configurar % comisión (rol Administrador)
- HU-F31 Gestionar usuarios (rol Administrador)
- HU-F32 Generar reporte empresarial (rol Administrador)
- HU-F33 Consultar dashboard ejecutivo (rol Junta Directiva)
- HU-F34 Auditar transacciones (rol Legal — UI dedicada)
- HU-F35 Restringir operaciones de usuario (rol Legal)

---

## 9. Checkpoints obligatorios

Estos son los momentos sin negociación donde se evalúa el plan:

| Checkpoint | Cuándo | Qué se decide |
|---|---|---|
| CP-1: Bootstrap completo | Cierre Día 0 | ¿Stack arrancando? Si no, parar e investigar antes de tocar features |
| CP-2: Mid Sprint 1 | Cierre Día 2 | ¿Auth+MFA funcionando? Si no, replantear Día 3-4 |
| CP-3: Cierre Sprint 1 | Cierre Día 5 | ¿Sprint 1 completo? Aplicar §3.4 si hay deuda |
| CP-4: Mid Sprint 2 | Cierre Día 7 | ¿Trading core funcionando? Si no, recortar dashboard antes que portafolio |
| CP-5: Pre-entrega | Cierre Día 9 | ¿Demo end-to-end funciona? Decidir si se promueven stretch goals |
| CP-6: Entrega final | Cierre Día 10 | DoD del MVP §6 marcado al 100% |

---

## 10. Versionado

Mismo proceso que `STACK.md`. Cualquier cambio de alcance se hace vía PR con justificación. Si el alcance se recorta o se promueve durante la ejecución, se documenta en este archivo y se referencia desde el Sprint Review correspondiente.

### Historial de cambios

| Fecha | Cambio | Razón |
|---|---|---|
| 2026-05-07 | Versión inicial | Cierre de fase de diseño, congelamiento de alcance del MVP |
