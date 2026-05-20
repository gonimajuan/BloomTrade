# tasks.md — HU-F01 Registrarse

> Descomposición granular del `plan.md` (SDD Paso 3). Cada tarea es verificable de forma independiente.
> Cadencia acordada: implementación en lotes lógicos, validación en hitos (no tras cada archivo).
> Rama: `feat/HU-F01-registrarse`. Commits con `refs HU-F01 specs/HU-F01-registrarse/SPEC.md` + `Co-authored-by: Claude`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho

---

## Lote A — Cimientos (deps, BD, error-handling) — ✅ commit `fe2f573`

- ☑ **T0.1** `pom.xml`: + `spring-boot-starter-mail`, `spring-boot-starter-thymeleaf`.
- ☑ **T0.2** `frontend/package.json`: + `@hookform/resolvers`.
- ☑ **T0.3** STACK.md §2.2, §3.2 e historial actualizados.
- ☑ **T1.1** Migración `V2__auth_users_and_balances.sql` (DDL literal spec §7.2).
- ☑ **T1.2** Verificación: Flyway aplica V2 sin error; tablas `app.users` y `app.user_balances` existen.  **← HITO 1 ✅**

## Lote B — `shared` + auditoría — ✅ commit `1481ea0`

- ☑ **T2.1** `shared/web/ErrorResponse.java`, `FieldErrorItem.java` (records, contrato global spec §6.3).
- ☑ **T2.2** `shared/web/TraceIdFilter.java` (UUID→MDC `traceId` + header `X-Trace-Id`).
- ☑ **T2.3** `shared/web/GlobalExceptionHandler.java` (`@RestControllerAdvice`) con regla D14.
- ☑ **T2.4** `validation-messages.properties` (código → mensaje humano ES).
- ☑ **T3.1** `audit/AuditEventType.java` (enum), `audit/AuditEvent.java` (record + builder hand-written).
- ☑ **T3.2** `audit/Auditor.java` (interfaz) + `audit/AuditLogger.java` (impl SLF4J + logstash-encoder).

## Lote C — Dominio, persistencia, validadores — ✅ commit `1481ea0`

- ☑ **T4.1** `portfolio/domain/UserBalance.java`.
- ☑ **T4.2** `portfolio/repository/UserBalanceRepository.java`.
- ☑ **T4.3** `portfolio/service/BalanceInitializer.java` + `DefaultBalanceInitializer.java`.
- ☑ **T5.1** Enums `auth/domain/UserRole`, `UserStatus`, `DocumentType`.
- ☑ **T5.2** `auth/domain/User.java`.
- ☑ **T5.3** `auth/repository/UserRepository.java` (`existsByEmailIgnoreCase`).
- ☑ **T5.4** `auth/validation/StrongPassword.java` + `PasswordPolicyValidator.java` (TAC-M3).
- ☑ **T5.5** `auth/validation/ConsistentDocumentNumber.java` + `DocumentNumberValidator.java` (class-level).
- ☑ **T6.1** `auth/dto/RegisterRequest.java`, `RegisterResponse.java`.
- ☑ **T6.2** `auth/mapper/UserMapper.java` (MapStruct).  **← HITO 2 ✅ `mvn compile` verde**

## Lote D — Notificación + servicio + controller + seguridad — ✅ commit `1481ea0`

- ☑ **T7.1** `config/AsyncConfig.java`.
- ☑ **T7.2** `notification/Notifier.java` + `WelcomeEmailDispatcher.java`.
- ☑ **T7.3** `resources/templates/email/welcome.html` (Thymeleaf).
- ☑ **T7.4** Config mail: `application.yml` (`spring.mail`) + `docker-compose.yml` (`SPRING_MAIL_HOST=mailhog`) + `.env.example` (`APP_BASE_URL`).
- ☑ **T8.1** `auth/event/UserRegisteredEvent.java` + `RegistrationEventListener.java`.
- ☑ **T8.2** `auth/service/RegisterService.java`.
- ☑ **T9.1** `auth/controller/RegisterController.java` (con anotaciones OpenAPI).
- ☑ **T10.1** `config/SecurityConfig.java`: `PasswordEncoder` BCrypt(12) + `permitAll` register.  **← HITO 3 ✅ `curl` 201**

## Lote E — Tests backend — ⏳ pendiente de commit

- ☑ **T11.1** `unit/auth/PasswordPolicyValidatorTest.java` (9 tests) + `DocumentNumberValidatorTest.java` (12 tests).
- ☑ **T11.2** `unit/auth/RegisterServiceTest.java` (4 tests) + `unit/notification/WelcomeEmailDispatcherTest.java` (2 tests).
- ☑ **T11.3** `integration/auth/RegisterFlowIT.java` (4 tests: 201, 409 case-insensitive, 400 TERMS D14, 400 WEAK_PASSWORD).
- ☑ **T11.4** `integration/OpenApiContractIT.java` (1 test: 201/400/409/500 documentados).
- ☑ **Build:** + `maven-failsafe-plugin` en `pom.xml` (corre `*IT` en `verify`).
- ☑ **Infra de test (D16):** pivot del perfil `test` de Testcontainers (incompatible con `dockerDesktopLinuxEngine` de Docker Desktop reciente) a Postgres del compose en `localhost:5433/bloomtrade_test`. + `management.health.mail.enabled=false` (fix `@MockBean JavaMailSender` ↔ `MailHealthContributor`).
- ☐ **T12.1** G3: verificar pipeline `logstash/pipeline/*.conf` rutea a `audit-events-{YYYY.MM}`.  **(deuda — diferida)**
- ☑ **HITO 4 ✅** `mvn verify` → **33/33 verde** (28 unit/smoke + 5 IT) + jacoco report.

## Lote F — Frontend — ⏳ pendiente de commit

- ☑ **T13.1** `vite.config.ts` ya tenía el proxy `/api`→`:8080` (Día 0).
- ☑ **T13.2** `main.tsx`: `BrowserRouter` + `QueryClientProvider`.
- ☑ **T13.3** `src/lib/apiClient.ts`, `src/lib/errorParser.ts`, `src/lib/messages.es.ts`, `src/types/api.ts`.
- ☑ **T14.1** `src/features/auth/schemas/register.ts` (zod, mirror servidor, cross-field doc/tipo).
- ☑ **T14.2** `src/features/auth/hooks/useRegister.ts` (mutación React Query, devuelve `ParsedError`).
- ☑ **T14.3** Componentes: `PasswordStrengthIndicator`, `PhoneInput`, `TermsCheckbox`, `RegisterForm`.
- ☑ **T14.4** Páginas: `RegisterPage`, `TermsPage`, `LoginPage` (stub para redirect 1.5s), rutas + guard `useSession` inerte (activable HU-F02).
- ☑ **T15.1** Tests Vitest+RTL: `RegisterForm.test.tsx` (3), `useRegister.test.tsx` (2), `errorParser.test.ts` (4) — **9 verdes en una pasada**.
- ☑ **HITO 5 ✅** `npm run lint && test && build` → 9/9 verde + bundle 329 KB (104 KB gzip).

## Lote G — Cierre — ⏳ en curso

- ☑ **T16.0** `APRENDIZAJES.md` actualizado con Día 1 ([[feedback-actualizar-aprendizajes]]).
- ☑ **T16.0b** `tasks.md` actualizado (este archivo).
- ☐ **T16.1** Verificación DoD spec §15 manual end-to-end (HITO 3 ya cubrió 201/BCrypt/balance/MailHog; falta Kibana audit-events-* hasta resolver T12.1).
- ☐ **T16.2** `docs/prompts/sprint-1.md` (bitácora de prompts) — diferida.
- ☐ **T16.3** PR `feat/HU-F01-registrarse` → `main` con plantilla DoD.

## Deuda nueva identificada en Día 1 (para post-HU-F01)

- **CI** (`.github/workflows/ci.yml`): agregar `service: postgres` con BD `bloomtrade_test` para que `mvn verify` corra en GitHub Actions (consecuencia del pivot D16).
- **Testcontainers**: investigar por qué docker-java 1.19.x no respeta `.testcontainers.properties` ni `DOCKER_HOST` con el pipe `dockerDesktopLinuxEngine`. Si se resuelve, revertir el yaml a la JDBC URL mágica.
- **Limpieza del entorno del usuario**: `Remove-Item $env:USERPROFILE\.testcontainers.properties` + reset de `DOCKER_HOST` (user env). Ambos inocuos si quedan.
- **PR que completa la SPEC** (Q1 del plan): §8.5 + §10 + ejemplo Gherkin "+57" alineado al regex del contrato (D15).
- **Logstash pipeline (G3 / T12.1)**: routing dedicado del marker `audit: true` al índice `audit-events-{YYYY.MM}`.
