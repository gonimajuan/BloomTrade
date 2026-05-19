# tasks.md — HU-F01 Registrarse

> Descomposición granular del `plan.md` (SDD Paso 3). Cada tarea es verificable de forma independiente.
> Cadencia acordada: implementación en lotes lógicos, validación en hitos (no tras cada archivo).
> Rama: `feat/HU-F01-registrarse`. Commits con `refs HU-F01 specs/HU-F01-registrarse/SPEC.md` + `Co-authored-by: Claude`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho

---

## Lote A — Cimientos (deps, BD, error-handling)

- ☐ **T0.1** `pom.xml`: + `spring-boot-starter-mail`, `spring-boot-starter-thymeleaf`.
- ☐ **T0.2** `frontend/package.json`: + `@hookform/resolvers`. (`npm install` lo corre el humano.)
- ☐ **T0.3** STACK.md actualizado (✔ hecho: §2.2, §3.2, historial 2026-05-19).
- ☐ **T1.1** Migración `V2__auth_users_and_balances.sql` (DDL literal spec §7.2).
- ☐ **T1.2** Verificación: `docker compose up` → Flyway aplica V2 sin error; tablas `app.users` y `app.user_balances` existen con índices/CHECKs.  **← HITO 1 (validación humana)**

## Lote B — `shared` + auditoría

- ☐ **T2.1** `shared/web/ErrorResponse.java`, `FieldErrorItem.java` (records, contrato global spec §6.3).
- ☐ **T2.2** `shared/web/TraceIdFilter.java` (UUID→MDC `traceId` + header `X-Trace-Id`).
- ☐ **T2.3** `shared/web/GlobalExceptionHandler.java` (`@RestControllerAdvice`): MethodArgumentNotValid → 400 `VALIDATION_FAILED` + `fieldErrors[]`; `EmailAlreadyRegisteredException` → 409; `RegistrationTechnicalException`/`Exception` → 500 `INTERNAL_ERROR`. Único catch genérico (CLAUDE.md #11).
- ☐ **T2.4** `ValidationMessages.properties` (código → mensaje humano ES).
- ☐ **T3.1** `audit/AuditEventType.java` (enum), `audit/AuditEvent.java` (record + builder, valida campos ARCHITECTURE.md §12).
- ☐ **T3.2** `audit/Auditor.java` (interfaz) + `audit/AuditLogger.java` (impl SLF4J + logstash-encoder).

## Lote C — Dominio, persistencia, validadores

- ☐ **T4.1** `portfolio/domain/UserBalance.java` (`@Table(schema="app",name="user_balances")`, `BigDecimal` NUMERIC(19,2)).
- ☐ **T4.2** `portfolio/repository/UserBalanceRepository.java`.
- ☐ **T4.3** `portfolio/service/BalanceInitializer.java` (interfaz) + `DefaultBalanceInitializer.java` (10000.00 USD).
- ☐ **T5.1** Enums `auth/domain/UserRole`, `UserStatus`, `DocumentType`.
- ☐ **T5.2** `auth/domain/User.java` (`@Table(schema="app",name="users")`).
- ☐ **T5.3** `auth/repository/UserRepository.java` (`existsByEmailIgnoreCase`).
- ☐ **T5.4** `auth/validation/StrongPassword.java` + `PasswordPolicyValidator.java` (TAC-M3).
- ☐ **T5.5** `auth/validation/ConsistentDocumentNumber.java` + `DocumentNumberValidator.java` (class-level).
- ☐ **T6.1** `auth/dto/RegisterRequest.java` (records + constraints + códigos D10), `RegisterResponse.java`.
- ☐ **T6.2** `auth/mapper/UserMapper.java` (MapStruct, sin `password_hash`).  **← HITO 2 (compila: `mvn -q compile`)**

## Lote D — Notificación + servicio + controller + seguridad

- ☐ **T7.1** `config/AsyncConfig.java` (`@EnableAsync` + Executor notificaciones).
- ☐ **T7.2** `notification/Notifier.java` + `dto/WelcomeEmailCommand.java` + `WelcomeEmailDispatcher.java` (`@Async`, JavaMailSender + Thymeleaf; `MailException` → `WELCOME_EMAIL_FAILED`).
- ☐ **T7.3** `resources/templates/email/welcome.html` (Thymeleaf, saludo `nombreCompleto` + link `/login`).
- ☐ **T7.4** Config mail: `application-dev.yml` (`spring.mail` host/port) + override `docker-compose.yml` (`SPRING_MAIL_HOST=mailhog`) + `.env.example` (ya tiene `MAIL_FROM`).
- ☐ **T8.1** `auth/event/UserRegisteredEvent.java` + `RegistrationEventListener.java` (`@TransactionalEventListener(AFTER_COMMIT)` → `Auditor` + email async).
- ☐ **T8.2** `auth/service/RegisterService.java` (`@Transactional`: chequeo email previo → `EmailAlreadyRegisteredException` + audit `EMAIL_DUPLICATE`; encode BCrypt 12; save User + `BalanceInitializer`; publish evento; catch `DataAccessException` → audit `TECHNICAL_ERROR` + `RegistrationTechnicalException`).
- ☐ **T9.1** `auth/controller/RegisterController.java` (`POST /api/v1/auth/register`, `@Valid`, extrae IP, anotaciones OpenAPI `@Operation`/`@ApiResponses` 201/400/409/500).
- ☐ **T10.1** `config/SecurityConfig.java`: + `@Bean PasswordEncoder` = `BCryptPasswordEncoder(12)` (G1/D13) + `permitAll` para `POST /api/v1/auth/register` (G2).  **← HITO 3 (arranca + registro manual `curl` 201)**

## Lote E — Tests backend

- ☐ **T11.1** Unit: `PasswordPolicyValidatorTest`, `DocumentNumberValidatorTest` (matrices Gherkin spec §11).
- ☐ **T11.2** Unit: `RegisterServiceTest` (happy, duplicado, error técnico).
- ☐ **T11.3** IT: `RegisterFlowIT` (Testcontainers PG; Redis autoconfig excluido D12; `@MockBean JavaMailSender`; `Auditor` capturado). Cubre todos los escenarios spec §11.
- ☐ **T11.4** Test de contrato OpenAPI (`/v3/api-docs` contiene el endpoint con 201/400/409/500).
- ☐ **T12.1** G3: verificar pipeline `logstash/pipeline/*.conf` rutea a `audit-events-{YYYY.MM}`; ajustar si hace falta.  **← HITO 4 (`mvn verify` verde + cobertura ≥ objetivo)**

## Lote F — Frontend

- ☐ **T13.1** `vite.config.ts`: proxy `/api` → `http://localhost:8080`.
- ☐ **T13.2** `main.tsx`: `BrowserRouter` + `QueryClientProvider`.
- ☐ **T13.3** `src/lib/apiClient.ts` (axios base + interceptors), `src/lib/errorParser.ts` (`ErrorResponse` → estado UI).
- ☐ **T14.1** `src/features/auth/schemas/register.ts` (zod, espeja validación servidor).
- ☐ **T14.2** `src/features/auth/hooks/useRegister.ts` (mutación React Query).
- ☐ **T14.3** Componentes: `PasswordStrengthIndicator`, `PhoneInput` (default +57), `TermsCheckbox`, `RegisterForm`.
- ☐ **T14.4** Páginas: `RegisterPage`, `TermsPage`; rutas `/register`, `/terms`; guard de sesión en `/register` (inerte hoy, activable HU-F02 — decisión usuario 2026-05-19).
- ☐ **T15.1** Tests Vitest+RTL: `RegisterForm.test`, `useRegister.test`, `errorParser.test`.  **← HITO 5 (`npm run lint && npm run test && npm run build` verde)**

## Lote G — Cierre

- ☐ **T16.1** Verificación DoD spec §15 end-to-end: `docker compose up` → registro real en `/register` → fila `app.users` con `password_hash ~ ^\$2a\$12\$` → balance 10000.00 → evento `USER_REGISTERED` en Kibana → email en MailHog (`:8025`).
- ☐ **T16.2** Actualizar `docs/prompts/sprint-1.md` (bitácora) y `APRENDIZAJES.md` (cierre Día 1, ver [[feedback-actualizar-aprendizajes]]).
- ☐ **T16.3** Abrir PR `feat/HU-F01-registrarse` con plantilla; checklist DoD; pipeline verde.  **← HITO 6 (entrega HU-F01)**
