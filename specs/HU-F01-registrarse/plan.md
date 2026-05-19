# plan.md — HU-F01 Registrarse

> Plan técnico derivado de `specs/HU-F01-registrarse/SPEC.md` v1.1.
> Estado: **pendiente de aprobación humana** (SDD Paso 2). No se escribe código hasta que apruebes este plan.
> Una vez aprobado → se produce `tasks.md` (Paso 3) y se implementa tarea por tarea (Paso 4).

---

## 1. Objetivo de la feature

Endpoint público `POST /api/v1/auth/register` + página `/register` que crean un inversionista
(`app.users` estado `ACTIVE`, rol `INVESTOR`), su balance inicial (`app.user_balances` = 10000.00 USD)
en una sola transacción ACID, emiten auditoría a ELK y disparan email de bienvenida vía MailHog,
con defensa en profundidad de validación (Zod cliente + Bean Validation servidor) y BCrypt cost 12.

---

## 2. Decisiones técnicas concretas (cerradas)

| # | Decisión | Justificación |
|---|---|---|
| D1 | Interfaces inter-módulo **sin prefijo `I`**, nombre de dominio: `Auditor` (AuditService), `Notifier` (NotificationService), `BalanceInitializer` (PortfolioService). | Resuelve inconsistencia ARCHITECTURE.md §5 vs CONVENTIONS.md §5.3 a favor de CONVENTIONS (decisión humana). Los nombres `IAudit/INotification/IBalanceInitializer` quedan conceptuales. |
| D2 | Impl de `BalanceInitializer` = `DefaultBalanceInitializer` (la interfaz toma el nombre que ARCHITECTURE.md §4 daba al componente; el impl se sufija `Default`). | Evita choque interfaz/impl manteniendo trazabilidad con ARCHITECTURE.md §4. |
| D3 | Email: Spring Mail + Thymeleaf. `welcome.html` como template Thymeleaf en `resources/templates/email/`. | Decisión humana. Reutilizable para emails de HU-F02 (MFA) / HU-F03. |
| D4 | `RegisterService` crea User **y** balance (vía `BalanceInitializer`) dentro del **mismo `@Transactional`**. | Spec §14 (Resolved) + ARCHITECTURE.md changelog 2.1. Compromiso pragmático MVP; refactor a event-driven = post-MVP. |
| D5 | `USER_REGISTERED` se emite **post-commit** vía `@TransactionalEventListener(phase = AFTER_COMMIT)`. El email asíncrono se dispara desde ese mismo listener. | Spec §5.1 pasos 13–14: auditoría y email son post-commit; el email es side-effect no crítico. |
| D6 | `USER_REGISTRATION_FAILED` (EMAIL_DUPLICATE) se emite **antes** de abrir transacción (chequeo previo). `USER_REGISTRATION_FAILED` (TECHNICAL_ERROR) se emite al capturar `DataAccessException` (excepción **específica**, permitida por CLAUDE.md #11) y se relanza como `RegistrationTechnicalException`. | Spec §5.3.1 / §5.3.5. No se viola la prohibición de catch genérico de `Exception` (solo el handler global lo hace). |
| D7 | `traceId` lo provee un `TraceIdFilter` (UUID por request → MDC + header `X-Trace-Id`). Sin dependencia de tracing (no hay Micrometer Tracing en STACK). El encoder logstash ya incluye MDC, así que los eventos de auditoría heredan el `traceId`. | STACK.md §9.3 exige `traceId`; no introduce dependencia nueva. Reutilizable en todo el proyecto. |
| D8 | Auditoría = log estructurado SLF4J con `logstash-logback-encoder` (StructuredArguments/Markers). `AuditEvent` (record) valida los campos obligatorios de ARCHITECTURE.md §12 **antes** de emitir. | ARCHITECTURE.md §7/§12, STACK.md §6.2/§6.3. El appender Logstash de dev/prod ya existe en `logback-spring.xml`. |
| D9 | Validación condicional `numeroDocumento`↔`tipoDocumento` = constraint **a nivel de clase** `@ConsistentDocumentNumber` sobre el record `RegisterRequest`. Política de password = constraint `@StrongPassword` + `PasswordPolicyValidator` (componente TAC-M3 de la spec §8.1). | Bean Validation de campo no resuelve cross-field; encapsular la política (TAC-M3). |
| D10 | Códigos de error de campo: cada constraint lleva como `message` el código SCREAMING_SNAKE (`WEAK_PASSWORD`, `VALIDATION_INVALID_EMAIL`, `VALIDATION_REQUIRED`, `TERMS_NOT_ACCEPTED`, etc.). El `GlobalExceptionHandler` arma `FieldErrorItem{field, code, message}` resolviendo el mensaje humano (ES) desde `ValidationMessages.properties`. | Cumple la matriz Gherkin de spec §11 sin acoplar lógica de presentación. |
| D11 | Frontend: componentes con **Tailwind plano** + `react-hook-form` + `zod`. **No** se introduce `shadcn/ui` (aprobado en STACK §3.2 pero no instalado y no exigido por spec §12; el plazo de 2 semanas no lo justifica). | Minimiza superficie y setup; todo ya aprobado e instalado salvo D13. |
| D12 | `RegisterFlowIT` excluye `RedisAutoConfiguration`/`RedisRepositoriesAutoConfiguration`. HU-F01 no toca Redis (spec §9.3); evita acoplar el test a infra de HU-F02. | Tests independientes (CONVENTIONS §7.4). |
| D13 | BCrypt con **strength 12** explícito: `@Bean PasswordEncoder` = `new BCryptPasswordEncoder(12)`. | Spec §5.1 paso 9 + §11.2 (hash debe empezar `$2a$12$`). El default de Spring es 10. |
| D14 | En errores de validación: si hay **exactamente un** campo con error, su código sube al `error` de primer nivel **y** queda en `fieldErrors[]`; si hay **varios**, `error="VALIDATION_FAILED"`. | Resuelve ambigüedad spec §6.1 (ejemplo `VALIDATION_FAILED`) vs §5.3.3/§5.3.4 + Gherkin §11 ("código `WEAK_PASSWORD`/`TERMS_NOT_ACCEPTED`"). Aprobado por el humano 2026-05-19. A documentar en el PR que completa la SPEC. |
| D15 | Teléfono: se implementa el regex del contrato OpenAPI §6.1 `^\+[1-9]\d{1,14}$` (admite vacío para deferir a `@NotBlank`). Con esto **"+57" es VÁLIDO**. | Inconsistencia interna SPEC §6.1 (schema) vs §11 (Gherkin marca "+57" inválido). El contrato OpenAPI es autoritativo. Aprobado por el humano 2026-05-19. El PR que completa la SPEC debe corregir el ejemplo Gherkin "+57"→válido. |

---

## 3. Cambios de dependencias — requieren tu aprobación y tu edición de STACK.md

> CLAUDE.md #5/#6 y CONVENTIONS.md §11.5: yo propongo el diff, **vos editás `STACK.md` y firmás**.

**Backend `pom.xml` (+ STACK.md §2.2 tabla "Módulos de Spring"):**
- `org.springframework.boot:spring-boot-starter-mail` — envío SMTP a MailHog (ARCHITECTURE.md §5/§8 ya lo presuponen vía "Spring Mail"; faltaba en pom).
- `org.springframework.boot:spring-boot-starter-thymeleaf` — render de `welcome.html` (D3).

**Frontend `package.json` (+ STACK.md §3.2):**
- `@hookform/resolvers` — adaptador zod↔react-hook-form. Es el companion estándar del par ya aprobado `react-hook-form + zod` (STACK §3.2); sin él habría que cablear la validación a mano, contradiciendo CONVENTIONS §6.4(7).

Ninguna otra dependencia nueva. Si rechazás alguna, el plan se ajusta (p.ej. HTML plano sin Thymeleaf, o resolver zod manual).

---

## 4. Hallazgos / deuda de Día 0 a cubrir dentro de esta HU

| # | Hallazgo | Acción en HU-F01 |
|---|---|---|
| G1 | `SecurityConfig` **no** tiene bean `PasswordEncoder`, pese a que spec §4 y ROADMAP Día 0 DoD lo daban por hecho. | Agregar `@Bean PasswordEncoder` (BCrypt 12) — D13. |
| G2 | `SecurityConfig` solo permite actuator/swagger; `/api/v1/auth/register` caería en `anyRequest().authenticated()` → 401/403. | Agregar `POST /api/v1/auth/register` a `permitAll()` (solo ese endpoint público; login/MFA llegan en HU-F02). |
| G3 | Pendiente verificar que el pipeline `logstash/pipeline/*.conf` rutee eventos de auditoría al índice `audit-events-{YYYY.MM}` (STACK §6.4 exige 2 índices; spec §15 DoD exige verlos en Kibana). | Tarea de verificación; si el pipeline no separa auditoría de app-logs, ajustar la config (acción "configurar herramientas", CONVENTIONS §11.4). |
| G4 | Frontend Día 0 es placeholder: sin Router, sin QueryClientProvider, sin proxy Vite, sin `apiClient`. | Cablear `BrowserRouter` + `QueryClientProvider` en `main.tsx`, proxy `/api`→`:8080` en `vite.config.ts`, e introducir `apiClient`/`errorParser` (reutilizables, spec §12.3). |

> Nota spec: la SPEC referencia "§8.5" (detalle de la desviación del balance) y omite el número §10 — salta §8.4 → §9 → §11. La **decisión** está cubierta por §14 (Resolved) + ARCHITECTURE.md changelog 2.1, así que no bloquea. Queda como pregunta abierta (§10 de este plan) para que decidas si querés que se complete la numeración de la SPEC en un PR de docs aparte.

---

## 5. Mapeo arquitectónico (spec §8) → paquetes Java

```
auth/                                   (AuthService — iniciador)
├── controller/RegisterController.java          @RestController, doc OpenAPI, extrae IP
├── service/RegisterService.java                @Service @Transactional (D4, D6)
├── repository/UserRepository.java              existsByEmailIgnoreCase, save
├── domain/User.java                            @Entity @Table(schema="app",name="users")
├── domain/UserRole.java | UserStatus.java | DocumentType.java   enums
├── dto/RegisterRequest.java | RegisterResponse.java             records
├── mapper/UserMapper.java                      MapStruct (entity→RegisterResponse, sin hash)
├── validation/StrongPassword.java + PasswordPolicyValidator.java     (TAC-M3, spec §8.1)
├── validation/ConsistentDocumentNumber.java + DocumentNumberValidator.java   (class-level)
├── event/UserRegisteredEvent.java              ApplicationEvent (post-commit)
├── event/RegistrationEventListener.java        @TransactionalEventListener(AFTER_COMMIT)
└── exception/EmailAlreadyRegisteredException.java | RegistrationTechnicalException.java

portfolio/                              (PortfolioService — receptor)
├── domain/UserBalance.java                     @Entity @Table(schema="app",name="user_balances")
├── repository/UserBalanceRepository.java
└── service/BalanceInitializer.java (interfaz) + DefaultBalanceInitializer.java (impl, D2)

audit/                                  (AuditService — notificado)
├── Auditor.java (interfaz)                      record(AuditEvent)
├── AuditLogger.java (impl)                      SLF4J + logstash-logback-encoder (D8)
├── AuditEvent.java (record + builder)           valida campos ARCHITECTURE.md §12
└── AuditEventType.java (enum)                   USER_REGISTERED, USER_REGISTRATION_FAILED, WELCOME_EMAIL_FAILED, ...

notification/                           (NotificationService — notificado)
├── Notifier.java (interfaz)                     sendWelcomeEmail(WelcomeEmailCommand)
├── WelcomeEmailDispatcher.java (impl)           @Async, JavaMailSender + Thymeleaf, MailException→WELCOME_EMAIL_FAILED
└── dto/WelcomeEmailCommand.java

shared/                                 (reutilizable — spec §6.3)
├── web/ErrorResponse.java | FieldErrorItem.java        records (contrato global)
├── web/GlobalExceptionHandler.java                     @RestControllerAdvice (único catch genérico, CLAUDE.md #11)
└── web/TraceIdFilter.java                               OncePerRequestFilter (D7)

config/
├── AsyncConfig.java                    @EnableAsync + Executor dedicado a notificaciones
└── SecurityConfig.java  (MODIFICAR)    + PasswordEncoder bean (G1/D13) + permitAll register (G2)
```

Interfaces consumidas (spec §8.2): `RegisterService` consume `Auditor`, `Notifier` (vía evento) y `BalanceInitializer`. Inyección por constructor (CONVENTIONS §5.4.7).

**Desviación estructural documentada:** se añade subpaquete `validation/` y `event/` en `auth/` (no listados en CONVENTIONS §5.5). Justificación: cohesión y reutilización del constraint encapsulado (TAC-M3). Se registrará en el PR (CONVENTIONS §13).

---

## 6. Base de datos

`backend/src/main/resources/db/migration/V2__auth_users_and_balances.sql` — **literal** del DDL de spec §7.2 (tablas `app.users`, `app.user_balances`, índices `idx_users_email_lower` único sobre `LOWER(email)`, CHECKs de enums y `chk_balance_nonneg`). Migración nueva, inmutable una vez mergeada (CLAUDE.md #12). JPA `ddl-auto=validate` ya activo → los `@Entity` deben mapear exactamente columnas/escala (`NUMERIC(19,2)`→`BigDecimal` para balance, STACK §4.2).

---

## 7. Orden de implementación y dependencias entre tareas

> Detalle granular vendrá en `tasks.md` (Paso 3). Aquí solo el orden y las dependencias.

```
T0  Dependencias: pom.xml (mail+thymeleaf) · package.json (@hookform/resolvers)   [requiere tu OK + tu edición STACK.md]
        │
T1  Migración V2 + verificación Flyway aplica (docker compose up)
        │
T2  shared/web: ErrorResponse, FieldErrorItem, TraceIdFilter, GlobalExceptionHandler        ← base de todo el error-handling
        │
T3  audit: AuditEvent, AuditEventType, Auditor, AuditLogger        (depende T2 por traceId/MDC)
        │
T4  portfolio: UserBalance, UserBalanceRepository, BalanceInitializer + DefaultBalanceInitializer
        │
T5  auth dominio+persistencia: enums, User, UserRepository, validators (@StrongPassword, @ConsistentDocumentNumber)
        │
T6  auth DTOs + UserMapper (MapStruct)
        │
T7  notification: Notifier, WelcomeEmailDispatcher, welcome.html, AsyncConfig   (depende T3 para WELCOME_EMAIL_FAILED)
        │
T8  auth servicio: RegisterService (+ UserRegisteredEvent + RegistrationEventListener)   (depende T3,T4,T5,T6,T7)
        │
T9  auth controller: RegisterController + anotaciones OpenAPI   (depende T8)
        │
T10 SecurityConfig: PasswordEncoder bean (D13/G1) + permitAll register (G2)
        │
T11 Tests backend: unit (PasswordPolicyValidatorTest, DocumentNumberValidatorTest, RegisterServiceTest)
                   + integración (RegisterFlowIT con Testcontainers) + contrato OpenAPI
        │
T12 G3: verificar/ajustar pipeline logstash → índice audit-events-*
        │
T13 Frontend infra: vite proxy, main.tsx (Router+QueryClient), apiClient, errorParser
        │
T14 Frontend feature: registerSchema (zod), useRegister, RegisterForm, PasswordStrengthIndicator,
                      PhoneInput, TermsCheckbox, RegisterPage, TermsPage, rutas /register /terms
        │
T15 Tests frontend: RegisterForm.test, useRegister.test, errorParser.test (Vitest+RTL)
        │
T16 Verificación DoD spec §15 end-to-end (docker compose: registro real → Kibana → MailHog → BCrypt en BD)
```

Cada tarea (T1…) se valida con vos antes de seguir a la siguiente (CLAUDE.md Paso 4).

---

## 8. Estrategia de tests (CONVENTIONS §7, spec §15)

**Unitarios** (`src/test/java/.../unit/`, Mockito):
- `PasswordPolicyValidatorTest` — matriz spec §11 (vacío→`VALIDATION_REQUIRED`, `Short1`/`alllowercase123`/`NOLOWERCASE123`/`NoNumbersHere`→`WEAK_PASSWORD`, `ValidPass123`→ok).
- `DocumentNumberValidatorTest` — matriz CC/CE/PASAPORTE de spec §11.
- `RegisterServiceTest` — happy path (mocks repo/encoder/balanceInit/publisher); email duplicado→`EmailAlreadyRegisteredException`+audit `EMAIL_DUPLICATE`; `DataAccessException`→audit `TECHNICAL_ERROR`+`RegistrationTechnicalException`.

**Integración** (`src/test/java/.../integration/`, sufijo `IT`, Testcontainers Postgres, D12):
- `RegisterFlowIT` — MockMvc + Postgres real; `@MockBean JavaMailSender`; `Auditor` capturado vía Logback `ListAppender` (o `@SpyBean`). Cubre todos los escenarios Gherkin de spec §11: 201 + filas + `password_hash` empieza `$2a$12$` + balance 10000.00; 409 duplicado (incl. distinta capitalización); `aceptaTerminos=false`→400 `TERMS_NOT_ACCEPTED` sin audit; outlines parametrizados de password/email/telefono/documento; error técnico (repo `@MockBean` lanza)→500 `INTERNAL_ERROR`+audit; fallo de mail (JavaMailSender lanza)→201 + `USER_REGISTERED` + `WELCOME_EMAIL_FAILED`.
- Test de contrato OpenAPI: `/v3/api-docs` contiene `POST /api/v1/auth/register` con 201/400/409/500 (CONVENTIONS §7.2).

**Frontend** (Vitest + RTL):
- `RegisterForm.test.tsx` — mensajes inline, submit deshabilitado hasta válido+términos, sin request si email vacío.
- `useRegister.test.ts` — mutación contra `apiClient` mockeado (201, 409, 400, 500).
- `errorParser.test.ts` — mapea `ErrorResponse`→estado UI.
- Sin E2E (CONVENTIONS §7.3).

Cobertura objetivo: ≥80% en `service/`+`domain/` de la HU (CONVENTIONS §7.1); jacoco ya configurado.

---

## 9. Trazabilidad criterios de aceptación → artefacto

| Escenario Gherkin spec §11 | Verificado por |
|---|---|
| Registro exitoso (201, filas, audit, mail, BCrypt $2a$12$) | `RegisterFlowIT#shouldRegister...`, BCrypt en `RegisterServiceTest` + IT |
| Email ya registrado / distinta capitalización (409) | `RegisterFlowIT` (índice único `LOWER(email)` + `existsByEmailIgnoreCase`) |
| Términos no aceptados (400 `TERMS_NOT_ACCEPTED`, sin audit) | `RegisterFlowIT` + `@AssertTrue` |
| Campos faltantes en frontend (botón disabled, sin request) | `RegisterForm.test.tsx` (zod) |
| Outlines password / email / telefono / documento | unit validators + `RegisterFlowIT` parametrizado |
| Error técnico (500, audit `TECHNICAL_ERROR`) | `RegisterFlowIT` + `RegisterServiceTest` |
| MailHog falla pero persiste (201 + `WELCOME_EMAIL_FAILED`) | `RegisterFlowIT` (`@MockBean JavaMailSender`) |
| RNF: hash nunca en logs / no plaintext en BD | revisión + assert `password_hash` ~ `^\$2a\$12\$` en IT |

---

## 10. Preguntas abiertas (no bloquean implementación)

| # | Pregunta | Propuesta |
|---|---|---|
| Q1 | La SPEC omite §8.5 y §10 (numeración salta §8.4→§9→§11) aunque §14 referencia §8.5. ¿Completar la SPEC en PR de docs aparte? | Sí, PR `docs` separado post-HU para no contaminar el PR de feature. Decisión tuya. |
| Q2 | Redirección `/register`→`/dashboard` si hay sesión (spec §12.1) — no hay sesión hasta HU-F02. | Implementar el guard pero inerte (no hay token aún); se activa con HU-F02. Documentar como diferido coherente con dependencia spec §3. |
| Q3 | `EMAIL_PROVIDER=mailhog|sendgrid` (STACK §7.5) — ¿abstraer el provider ya? | MVP: solo MailHog vía `JavaMailSender`. La interfaz `Notifier` ya desacopla; SendGrid = post-MVP. |
| Q4 | SPEC §6.1 (schema regex) vs §11 (Gherkin "+57" inválido) — resuelto D15 a favor del contrato. | El PR que completa la SPEC corrige el ejemplo Gherkin "+57"→válido (alineado a `^\+[1-9]\d{1,14}$`). |

---

## 11. Definition of Done de esta HU (spec §15 + CONVENTIONS §9)

Se considerará terminada cuando estén verdes todas las casillas de SPEC §15 y, además: PR <400 líneas razonable (CONVENTIONS §4.3 — si se desborda, se parte backend/frontend en dos PRs), pipeline verde, SonarCloud sin issues bloqueantes, `STACK.md` actualizado por vos con las 3 deps, commits con `refs HU-F01 specs/HU-F01-registrarse/SPEC.md` y trailer `Co-authored-by: Claude`.
