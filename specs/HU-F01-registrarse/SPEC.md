# spec.md — Registro de inversionista nuevo

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F01 (BT-2 en Jira) |
| Sprint | 1 |
| Prioridad MoSCoW | Must |
| Estado | Ready |
| Autor | *[Tu nombre]* |
| Fecha creación | 2026-05-08 |
| Última actualización | 2026-05-19 |
| Versión spec | 1.2 |
| Día estimado del ROADMAP | Día 1 |

---

## 2. Historia(s) de usuario

### HU-F01 — Registrarse

**Como** inversionista nuevo, **quiero** registrarme en el sistema con mis datos personales y credenciales, **para** acceder a la plataforma de trading y operar en los mercados internacionales.

---

## 3. Contexto y dependencias

### Por qué importa

Es el punto de entrada del sistema. Sin registro funcional, ningún otro flujo del MVP puede ejercitarse. Es la primera demostración de varias tácticas arquitectónicas (TAC-S4 auditoría, TAC-M3 encapsulación) y de la integración con MailHog vía NotificationService.

### Dependencias técnicas

- **Día 0 (Bootstrap completo)** — el stack Docker debe estar arriba con backend, PostgreSQL, Redis, ElasticSearch, MailHog
- **Migración inicial Flyway V1** con schemas `app`, `config`, `audit` creados
- **Spring Security configurado** con `BCryptPasswordEncoder` como bean activo

### Features que dependen de esta

- HU-F02 — Iniciar sesión (sin usuario registrado, no hay login)
- HU-F03 — Verificar código MFA
- HU-F04 — Configurar perfil
- HU-F06 — Suscribirse a plan premium
- Toda HU posterior del MVP

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario no autenticado | (ninguno) | Iniciador |
| Sistema BloomTrade | — | Receptor / procesador |

### Precondiciones del sistema

- Backend, BD, Redis y MailHog accesibles
- El usuario no tiene sesión JWT activa (si la tiene, frontend redirige a `/dashboard` antes de mostrar `/register`)
- La tabla `app.users` está accesible y consultable por email

### Datos requeridos en el sistema

- Schema `app` creado con tablas `users` y `user_balances` (creadas por la migración asociada — ver §7)
- Configuración Spring Security activa con `BCryptPasswordEncoder`
- MailHog corriendo y aceptando conexiones SMTP en `mailhog:1025` (referenciable desde el contenedor backend)
- Plantilla de email `welcome.html` disponible en classpath

---

## 5. Flujos

### 5.1 Flujo principal — registro exitoso

**Precondiciones específicas:** Ver §4

1. Usuario navega a `/register` en el frontend
2. Sistema muestra formulario vacío con campos obligatorios marcados
3. Usuario completa email, password, nombre completo, tipo y número de documento, teléfono, y marca el checkbox de aceptación de términos
4. Frontend valida campos en cliente con Zod (formato email, política de password, formato teléfono E.164, etc.)
5. Usuario presiona "Crear mi cuenta"
6. Frontend envía `POST /api/v1/auth/register` con el payload completo
7. Backend valida nuevamente todos los campos con Bean Validation (defensa en profundidad)
8. Backend verifica que el email no exista en `app.users` (consulta case-insensitive)
9. Backend hashea el password con `BCryptPasswordEncoder` (cost factor 12, default de Spring)
10. Backend abre transacción y crea registro en `app.users` con estado `ACTIVE`, rol `INVESTOR`, `acepto_terminos_at = NOW()`
11. Backend crea registro en `app.user_balances` con saldo inicial `10000.00 USD` (mismo transaction)
12. Backend hace commit de la transacción
13. Backend emite evento `USER_REGISTERED` a AuditService → ElasticSearch (post-commit)
14. Backend dispara envío asíncrono de email de bienvenida vía NotificationService → MailHog
15. Backend responde `201 Created` con datos básicos del usuario (sin password_hash)
16. Frontend muestra toast de éxito y redirige a `/login` tras 1.5s

**Postcondiciones:**
- Existe registro en `app.users` con los datos provistos y `password_hash` BCrypt
- Existe registro en `app.user_balances` con saldo `10000.00 USD`
- Existe evento `USER_REGISTERED` indexado en `audit-events-{YYYY.MM}` en ES
- Existe email de bienvenida visible en MailHog UI (`http://localhost:8025`)
- El usuario puede proceder a HU-F02 con sus credenciales

### 5.2 Flujos alternativos

No aplica. El registro es un flujo lineal sin variantes alternativas exitosas.

### 5.3 Flujos de error

#### 5.3.1 Email ya registrado

**Cuándo se dispara:** El email enviado en el request ya existe en `app.users` (comparación case-insensitive)
**Respuesta del sistema:** HTTP 409 Conflict con código `EMAIL_ALREADY_REGISTERED` y mensaje "El correo electrónico ya está registrado en el sistema"
**Estado final:** Sin cambios persistidos
**Evento de auditoría:** `USER_REGISTRATION_FAILED` con `details.reason = "EMAIL_DUPLICATE"`

#### 5.3.2 Validación de campos falló

**Cuándo se dispara:** Cualquier campo requerido está ausente, vacío, o no cumple su validación de formato/longitud
**Respuesta del sistema:** HTTP 400 Bad Request con código `VALIDATION_FAILED` y array `fieldErrors[]` listando los campos con error
**Estado final:** Sin cambios persistidos
**Evento de auditoría:** No se emite. Las validaciones de entrada no se auditan — son ruido y no aportan valor forense.

#### 5.3.3 Password no cumple política

**Cuándo se dispara:** Password tiene menos de 10 caracteres, o no incluye al menos 1 mayúscula, 1 minúscula y 1 número
**Respuesta del sistema:** HTTP 400 Bad Request con código `WEAK_PASSWORD` en `fieldErrors[]` para el campo password
**Estado final:** Sin cambios persistidos
**Evento de auditoría:** No se emite

#### 5.3.4 Términos no aceptados

**Cuándo se dispara:** El campo `aceptaTerminos` es `false` o ausente
**Respuesta del sistema:** HTTP 400 Bad Request con código `TERMS_NOT_ACCEPTED`
**Estado final:** Sin cambios persistidos
**Evento de auditoría:** No se emite

#### 5.3.5 Error técnico al persistir

**Cuándo se dispara:** Falla de conexión con PostgreSQL, error en la transacción, o cualquier error inesperado durante la creación
**Respuesta del sistema:** HTTP 500 Internal Server Error con código `INTERNAL_ERROR` y mensaje genérico (sin exponer detalles técnicos al cliente)
**Estado final:** Transacción rollback automático por Spring `@Transactional` — sin cambios persistidos
**Evento de auditoría:** `USER_REGISTRATION_FAILED` con `details.reason = "TECHNICAL_ERROR"` y `details.errorClass`. Stack trace completo va al log de aplicación con nivel ERROR, NO al evento de auditoría (PII concerns).

#### 5.3.6 NotificationService no logra enviar email de bienvenida

**Cuándo se dispara:** MailHog no responde, SMTP timeout, o cualquier error en el envío
**Respuesta del sistema:** HTTP 201 Created (el registro se considera exitoso aunque el email falle — el email es side effect, no parte del flujo crítico)
**Estado final:** Usuario y balance creados correctamente. Email no enviado.
**Evento de auditoría:** `USER_REGISTERED` se emite normalmente. Adicionalmente: `WELCOME_EMAIL_FAILED` (warning, no error).

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

#### 6.1.1 `POST /api/v1/auth/register`

**Propósito:** Crear cuenta de inversionista nueva con auto-verificación.

**Auth requerido:** No (endpoint público)

```yaml
paths:
  /api/v1/auth/register:
    post:
      summary: Registra un nuevo inversionista en BloomTrade
      tags: [Authentication]
      security: []  # Endpoint público
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/RegisterRequest'
            example:
              email: "juan.perez@example.com"
              password: "SecurePass123"
              nombreCompleto: "Juan Pérez García"
              tipoDocumento: "CC"
              numeroDocumento: "1234567890"
              telefono: "+573001234567"
              aceptaTerminos: true
      responses:
        '201':
          description: Usuario registrado exitosamente
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RegisterResponse'
              example:
                id: "550e8400-e29b-41d4-a716-446655440000"
                email: "juan.perez@example.com"
                nombreCompleto: "Juan Pérez García"
                rol: "INVESTOR"
                estado: "ACTIVE"
                createdAt: "2026-05-08T14:32:18.123Z"
        '400':
          description: Validación de entrada falló o términos no aceptados
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
              example:
                timestamp: "2026-05-08T14:32:18.123Z"
                status: 400
                error: "VALIDATION_FAILED"
                message: "Uno o más campos no superaron la validación"
                path: "/api/v1/auth/register"
                traceId: "9d3e8f2a-1c4b-4f6e-8a7d-2b9c1e5f8a3b"
                fieldErrors:
                  - field: "email"
                    code: "VALIDATION_INVALID_EMAIL"
                    message: "Formato de email inválido"
                  - field: "password"
                    code: "WEAK_PASSWORD"
                    message: "Password debe tener al menos 10 caracteres, una mayúscula, una minúscula y un número"
        '409':
          description: Email ya registrado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
              example:
                timestamp: "2026-05-08T14:32:18.123Z"
                status: 409
                error: "EMAIL_ALREADY_REGISTERED"
                message: "El correo electrónico ya está registrado en el sistema"
                path: "/api/v1/auth/register"
                traceId: "9d3e8f2a-1c4b-4f6e-8a7d-2b9c1e5f8a3b"
        '500':
          description: Error técnico inesperado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

components:
  schemas:
    RegisterRequest:
      type: object
      required: [email, password, nombreCompleto, tipoDocumento, numeroDocumento, telefono, aceptaTerminos]
      properties:
        email:
          type: string
          format: email
          maxLength: 254
          description: Email único del usuario (case-insensitive)
          example: "juan.perez@example.com"
        password:
          type: string
          minLength: 10
          maxLength: 100
          description: Password en plaintext. Será hasheado server-side con BCrypt.
          example: "SecurePass123"
        nombreCompleto:
          type: string
          minLength: 3
          maxLength: 100
          description: Nombre y apellidos completos
          example: "Juan Pérez García"
        tipoDocumento:
          type: string
          enum: [CC, CE, PASAPORTE]
          description: Tipo de documento de identidad
          example: "CC"
        numeroDocumento:
          type: string
          minLength: 6
          maxLength: 15
          description: Número de documento (validado por longitud según tipo)
          example: "1234567890"
        telefono:
          type: string
          pattern: '^\+[1-9]\d{1,14}$'
          description: Teléfono en formato E.164 (incluye + y código de país)
          example: "+573001234567"
        aceptaTerminos:
          type: boolean
          description: Debe ser true. Si es false o ausente, el registro es rechazado.
          example: true
    RegisterResponse:
      type: object
      properties:
        id:
          type: string
          format: uuid
        email:
          type: string
          format: email
        nombreCompleto:
          type: string
        rol:
          type: string
          enum: [INVESTOR, BROKER, ADMIN, LEGAL, BOARD]
        estado:
          type: string
          enum: [ACTIVE, BLOCKED, SUSPENDED]
        createdAt:
          type: string
          format: date-time
    ErrorResponse:
      type: object
      properties:
        timestamp:
          type: string
          format: date-time
        status:
          type: integer
        error:
          type: string
          description: Código de error en SCREAMING_SNAKE_CASE
        message:
          type: string
          description: Mensaje legible para humanos en español
        path:
          type: string
        traceId:
          type: string
          format: uuid
        fieldErrors:
          type: array
          description: Solo presente cuando hay errores de validación de campo
          items:
            type: object
            properties:
              field:
                type: string
              code:
                type: string
              message:
                type: string
```

**Validaciones de campo (server-side):**

| Campo | Regla | Error si falla |
|---|---|---|
| `email` | Formato RFC 5322, max 254 chars, único en `app.users` (case-insensitive) | `VALIDATION_INVALID_EMAIL` o `EMAIL_ALREADY_REGISTERED` |
| `password` | 10-100 chars, ≥1 mayúscula, ≥1 minúscula, ≥1 número | `WEAK_PASSWORD` |
| `nombreCompleto` | 3-100 chars, no solo whitespace, permite letras Unicode + espacios + tildes | `VALIDATION_INVALID_NAME` |
| `tipoDocumento` | Enum estricto: `CC`, `CE`, `PASAPORTE` | `VALIDATION_INVALID_DOCUMENT_TYPE` |
| `numeroDocumento` | CC/CE: 6-12 dígitos numéricos. PASAPORTE: 6-15 chars alfanuméricos. | `VALIDATION_INVALID_DOCUMENT_NUMBER` |
| `telefono` | Formato E.164: `+` seguido de 1-15 dígitos, primer dígito ≠ 0 | `VALIDATION_INVALID_PHONE` |
| `aceptaTerminos` | Debe ser exactamente `true` (booleano) | `TERMS_NOT_ACCEPTED` |

**Códigos de error específicos del endpoint:**

| Código | HTTP | Cuándo se devuelve |
|---|---|---|
| `EMAIL_ALREADY_REGISTERED` | 409 | Email duplicado en `app.users` |
| `WEAK_PASSWORD` | 400 | Password no cumple política |
| `TERMS_NOT_ACCEPTED` | 400 | Checkbox no marcado |
| `VALIDATION_FAILED` | 400 | Genérico para errores de validación de campo |
| `INTERNAL_ERROR` | 500 | Error técnico inesperado |

### 6.2 Endpoints modificados

No aplica.

### 6.3 Esquemas de datos compartidos

`ErrorResponse` se reutilizará en **todos los endpoints del proyecto**. Esta spec lo introduce por primera vez. Cualquier futura modificación a este esquema requiere actualizar **todas** las specs que lo usan.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Archivo:** `backend/src/main/resources/db/migration/V2__auth_users_and_balances.sql`

**Schema afectado:** `app`

### 7.2 Tablas nuevas

#### Tabla `app.users`

```sql
CREATE TABLE app.users (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(254) NOT NULL,
    password_hash       VARCHAR(60)  NOT NULL,
    nombre_completo     VARCHAR(100) NOT NULL,
    tipo_documento      VARCHAR(15)  NOT NULL,
    numero_documento    VARCHAR(15)  NOT NULL,
    telefono            VARCHAR(20)  NOT NULL,
    rol                 VARCHAR(20)  NOT NULL DEFAULT 'INVESTOR',
    estado              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    acepto_terminos_at  TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_users_rol     CHECK (rol IN ('INVESTOR', 'BROKER', 'ADMIN', 'LEGAL', 'BOARD')),
    CONSTRAINT chk_users_estado  CHECK (estado IN ('ACTIVE', 'BLOCKED', 'SUSPENDED')),
    CONSTRAINT chk_users_tipo_doc CHECK (tipo_documento IN ('CC', 'CE', 'PASAPORTE'))
);

CREATE UNIQUE INDEX idx_users_email_lower ON app.users (LOWER(email));
CREATE INDEX idx_users_rol    ON app.users (rol);
CREATE INDEX idx_users_estado ON app.users (estado);
```

**Justificación de campos:**
- `password_hash VARCHAR(60)`: BCrypt produce hashes de exactamente 60 caracteres. Reservar más sería desperdicio.
- `email VARCHAR(254)`: longitud máxima por RFC 5321
- `acepto_terminos_at`: timestamp del momento exacto en que aceptó (compliance/legal)
- `rol` y `estado` con CHECK constraints para integridad — la BD rechaza valores fuera de los enums
- `idx_users_email_lower`: índice único case-insensitive para búsqueda y constraint de unicidad

#### Tabla `app.user_balances`

```sql
CREATE TABLE app.user_balances (
    user_id      UUID PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
    balance      NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    currency     VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_nonneg CHECK (balance >= 0)
);
```

**Justificación de campos:**
- `NUMERIC(19, 2)`: precisión decimal para dinero (regla inviolable de `STACK.md` §4.2)
- `chk_balance_nonneg`: invariante crítico — el saldo nunca debe quedar negativo, ni siquiera por bug de código
- `currency` solo USD por ahora pero campo presente para multi-moneda futura
- `ON DELETE CASCADE`: si un usuario se elimina, su balance se borra automáticamente

### 7.3 Tablas modificadas

No aplica.

### 7.4 Datos semilla (seeds)

No aplica para HU-F01.

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

| Módulos | Rol | Componentes específicos tocados |
|---|---|---|
| AuthService | Iniciador | `RegisterController`, `RegisterService`, `UserRepository`, `PasswordPolicyValidator` |
| PortfolioService | Receptor | `BalanceRepository`, `BalanceInitializer` (componente nuevo) |
| AuditService | Notificado | `AuditLogger` |
| NotificationService | Notificado | `WelcomeEmailDispatcher`, plantilla `welcome.html` |

### 8.2 Interfaces consumidas

| Interfaz | Módulo que la expone | Para qué se usa aquí |
|---|---|---|
| `IAudit` | AuditService | Registrar `USER_REGISTERED` o `USER_REGISTRATION_FAILED` |
| `INotification` | NotificationService | Disparar email de bienvenida |

### 8.3 Interfaces expuestas

Ninguna nueva. Esta feature no expone interfaces hacia otros módulos.

### 8.4 Tácticas de Bass aplicadas

| Táctica | ID | Cómo se materializa en esta feature |
|---|---|---|
| Mantener registro de auditoría | TAC-S4 | Eventos `USER_REGISTERED`, `USER_REGISTRATION_FAILED`, `WELCOME_EMAIL_FAILED` emitidos a ElasticSearch |
| Encapsular | TAC-M3 | Política de password encapsulada en `PasswordPolicyValidator`; lógica de hashing encapsulada en `BCryptPasswordEncoder` (Spring Security) |

### 8.5 Desviación arquitectónica aceptada: creación del balance inicial

**Contexto.** ARCHITECTURE.md §3 sitúa `BalanceInitializer` dentro del módulo PortfolioService. Sin embargo, el registro de un nuevo inversionista debe crear `app.users` **y** `app.user_balances` en la **misma transacción ACID** (regla de negocio: un usuario sin balance no debe poder existir aunque sea por una ventana de milisegundos).

**Decisión.** `AuthService.RegisterService` (módulo Auth) invoca a `PortfolioService.BalanceInitializer` (módulo Portfolio) dentro de su propia `@Transactional`. La interfaz la expone PortfolioService (`BalanceInitializer`, sin prefijo `I` por CONVENTIONS §5.3 — ver `plan.md` decisión D1); la consume directamente Auth. Esto cruza una frontera de módulo dentro de la misma transacción.

**Por qué se acepta el cruce.**
- **Atomicidad financiera no negociable:** el balance debe nacer con el usuario, en una sola transacción ACID. Una alternativa event-driven (publicar `UserRegistered` y que un listener de Portfolio cree el balance asíncrono) introduciría una ventana de inconsistencia (usuario sin balance) y requeriría compensación si la creación del balance fallara post-commit del usuario.
- **Costo del workaround:** habilitar consistencia eventual con saga compensatoria o outbox pattern es inviable para el plazo de 2 semanas del MVP.
- **Encapsulación preservada:** Portfolio sigue siendo dueña de la lógica del balance (`BalanceInitializer.initializeBalance(userId)`); Auth no conoce el modelo de datos de `app.user_balances`. La dependencia es **por interfaz**, no por entidad.

**Plan de refactor post-MVP.** Migrar a evento de dominio + listener en Portfolio + outbox pattern para garantizar at-least-once y desacoplar transaccionalmente los módulos. Registrado como deuda en `ROADMAP.md` post-MVP.

**Impacto en ARCHITECTURE.md.** El changelog 2.1 de ARCHITECTURE.md (2026-05-08) ya documenta esta dependencia: *"PortfolioService EXPONE IBalanceInitializer CONSUME AuthService.RegisterService"*. Esta §8.5 es la justificación que ese changelog adelantaba.

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

| `event_type` | Trigger | Campos extra en `details` |
|---|---|---|
| `USER_REGISTERED` | Registro exitoso completado (post-commit) | `{ userId, email, rol, registrationMethod: "WEB_FORM" }` |
| `USER_REGISTRATION_FAILED` | Error técnico o email duplicado | `{ attemptedEmail, reason: "EMAIL_DUPLICATE" \| "TECHNICAL_ERROR", errorClass }` |
| `WELCOME_EMAIL_FAILED` | Email de bienvenida no se pudo enviar (warning, registro sí exitoso) | `{ userId, emailProvider: "mailhog", errorMessage }` |

### 9.2 Notificaciones

| Trigger | Canal | Asunto / Plantilla | Contenido resumido |
|---|---|---|---|
| Registro exitoso | Email (MailHog en dev) | Asunto: "Bienvenido a BloomTrade" / Plantilla: `welcome.html` | Saludo personalizado con `nombreCompleto`, mensaje breve dando la bienvenida, link a `/login` para iniciar sesión |

### 9.3 Cambios en caché Redis

No aplica. El registro no toca Redis.

### 9.4 Llamadas a APIs externas

| API externa | Endpoint | Adapter | Cuándo se invoca |
|---|---|---|---|
| MailHog (SMTP) | `mailhog:1025` | Spring Mail (vía `JavaMailSender`) | Después del commit de la transacción de registro, asíncronamente |

> Nota: MailHog no requiere `IntegrationService` adapter porque es protocolo SMTP estándar manejado por Spring Mail. Esto es coherente con `ARCHITECTURE.md` §8 — el adapter es para APIs HTTP de terceros (Alpaca, Polygon, Stripe, Twilio).

---

## 10. Riesgos y mitigaciones

| # | Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|---|
| R1 | El email de bienvenida falla silenciosamente (MailHog caído) y el usuario nuevo no recibe confirmación | Baja en dev / Media en prod | Bajo | El flujo no bloquea: 201 se devuelve igual; se emite evento `WELCOME_EMAIL_FAILED` a auditoría para reintento manual o automático (post-MVP). Cubierto por test unit `WelcomeEmailDispatcherTest`. |
| R2 | Carrera por el índice único `idx_users_email_lower` entre dos POST simultáneos con el mismo email | Baja | Bajo | `RegisterService` captura `DataIntegrityViolationException` y la mapea a 409 `EMAIL_ALREADY_REGISTERED` con audit `EMAIL_DUPLICATE`. Cubierto por test unit `RegisterServiceTest#shouldMapUniqueIndexRaceToEmailDuplicate`. |
| R3 | `password_hash` o el password plaintext aparece en logs JSON (Logback/Logstash) por accidente | Baja | Alto (compliance/PII) | (1) DTOs no se serializan a logs — sólo el response sin hash, ver `UserMapper`. (2) Excepciones técnicas mandan stack al log de aplicación pero NO al evento de auditoría (§5.3.5). (3) RNF §11.2 lo verifica vía inspección manual en Kibana. |
| R4 | BCrypt cost 12 lentifica el endpoint por debajo del SLO (<1s p95) bajo concurrencia | Baja en MVP / Media en producción | Medio | RNF §11.2 mide p95; si se desborda, alternativas: (a) bajar cost a 10 documentándolo, (b) introducir cola de hash. Por ahora se acepta el costo por seguridad (D13 del plan). |
| R5 | El `Notifier` asíncrono retrasa la liberación del thread del request si `notificationExecutor` se satura | Baja | Bajo | El executor tiene queue cap 50 (`AsyncConfig`); si se llena, la tarea se rechaza y se audita `WELCOME_EMAIL_FAILED`. El request HTTP nunca espera por el envío de mail. |
| R6 | El cliente envía caracteres no-ASCII en `nombreCompleto` y se corrompen en BD por encoding | Baja | Bajo | Postgres en UTF-8 (`postgres:16-alpine` default), JPA/Hibernate UTF-8 default, `Content-Type: application/json; charset=UTF-8`. Verificado en HITO 3 con `Juan Pérez García` round-trip a BD (`char_length=17`, `octet_length=19`). |

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Registro de inversionista nuevo

  Antecedentes:
    Dado que el sistema BloomTrade está corriendo
    Y la base de datos tiene el schema "app" con tablas users y user_balances vacías
    Y MailHog está disponible en mailhog:1025

  Escenario: Registro exitoso de inversionista nuevo
    Dado un usuario no autenticado en la página /register
    Cuando completa el formulario con:
      | campo            | valor                       |
      | email            | nuevo@example.com           |
      | password         | SecurePass123               |
      | nombreCompleto   | Juan Pérez García           |
      | tipoDocumento    | CC                          |
      | numeroDocumento  | 1234567890                  |
      | telefono         | +573001234567               |
      | aceptaTerminos   | true                        |
    Y envía el formulario
    Entonces el sistema responde 201 Created
    Y se crea un registro en app.users con rol INVESTOR y estado ACTIVE
    Y se crea un registro en app.user_balances con balance=10000.00 y currency=USD
    Y se emite un evento USER_REGISTERED a ElasticSearch
    Y se envía un email de bienvenida visible en MailHog
    Y el password queda hasheado con BCrypt (60 chars empezando con "$2a$12$")

  Escenario: Email ya registrado
    Dado que existe un usuario con email "existente@example.com" en app.users
    Cuando se envía POST /api/v1/auth/register con email="existente@example.com" y demás campos válidos
    Entonces el sistema responde 409 Conflict con código EMAIL_ALREADY_REGISTERED
    Y NO se crea ningún registro nuevo en app.users
    Y NO se crea ningún registro en app.user_balances
    Y se emite un evento USER_REGISTRATION_FAILED con reason="EMAIL_DUPLICATE"
    Y NO se envía email de bienvenida

  Escenario: Email duplicado con diferente capitalización
    Dado que existe un usuario con email "juan@example.com" en app.users
    Cuando se envía POST /api/v1/auth/register con email="JUAN@example.com"
    Entonces el sistema responde 409 Conflict con código EMAIL_ALREADY_REGISTERED
    Y NO se crea ningún registro nuevo

  Escenario: Términos no aceptados
    Dado un usuario completando el formulario
    Cuando envía el formulario con aceptaTerminos=false
    Entonces el sistema responde 400 Bad Request con código TERMS_NOT_ACCEPTED
    Y NO se crea ningún registro
    Y NO se emite evento de auditoría

  Escenario: Campos obligatorios faltantes en frontend
    Dado un usuario en el formulario de registro
    Cuando intenta enviar el formulario sin completar el campo email
    Entonces el frontend muestra mensaje "El correo es requerido" en el campo email
    Y el botón "Crear mi cuenta" permanece deshabilitado
    Y NO se realiza request al backend

  Esquema del escenario: Validación del campo password
    Cuando se envía POST /api/v1/auth/register con password=<valor> y demás campos válidos
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor              | httpStatus | errorCode            |
      | (vacío)            | 400        | VALIDATION_REQUIRED  |
      | "Short1"           | 400        | WEAK_PASSWORD        |
      | "alllowercase123"  | 400        | WEAK_PASSWORD        |
      | "NOLOWERCASE123"   | 400        | WEAK_PASSWORD        |
      | "NoNumbersHere"    | 400        | WEAK_PASSWORD        |
      | "ValidPass123"     | 201        | (none)               |

  Esquema del escenario: Validación del campo email
    Cuando se envía POST /api/v1/auth/register con email=<valor> y demás campos válidos
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor              | httpStatus | errorCode                |
      | (vacío)            | 400        | VALIDATION_REQUIRED      |
      | "no-arroba"        | 400        | VALIDATION_INVALID_EMAIL |
      | "@dominio.com"     | 400        | VALIDATION_INVALID_EMAIL |
      | "espacios @ x.com" | 400        | VALIDATION_INVALID_EMAIL |
      | "valid@example.com"| 201        | (none)                   |

  Esquema del escenario: Validación del campo telefono
    Cuando se envía POST /api/v1/auth/register con telefono=<valor> y demás campos válidos
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor             | httpStatus | errorCode                |
      | "3001234567"      | 400        | VALIDATION_INVALID_PHONE |
      | "+0123456789"     | 400        | VALIDATION_INVALID_PHONE |
      | "+57"             | 201        | (none)                   |
      | "+573001234567"   | 201        | (none)                   |

  Esquema del escenario: Validación del campo numeroDocumento según tipoDocumento
    Cuando se envía POST /api/v1/auth/register con tipoDocumento=<tipo> y numeroDocumento=<numero>
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | tipo      | numero          | httpStatus | errorCode                          |
      | CC        | "abc123"        | 400        | VALIDATION_INVALID_DOCUMENT_NUMBER |
      | CC        | "12345"         | 400        | VALIDATION_INVALID_DOCUMENT_NUMBER |
      | CC        | "1234567890"    | 201        | (none)                             |
      | PASAPORTE | "AB123456"      | 201        | (none)                             |
      | PASAPORTE | "AB"            | 400        | VALIDATION_INVALID_DOCUMENT_NUMBER |

  Escenario: Error técnico durante el registro
    Dado un usuario completando el formulario válidamente
    Y la base de datos está temporalmente inaccesible
    Cuando envía el formulario
    Entonces el sistema responde 500 Internal Server Error con código INTERNAL_ERROR
    Y se emite un evento USER_REGISTRATION_FAILED con reason="TECHNICAL_ERROR"
    Y NO se crea ningún registro

  Escenario: MailHog falla pero el registro sí persiste
    Dado un usuario completando el formulario válidamente
    Y MailHog está temporalmente inaccesible
    Cuando envía el formulario
    Entonces el sistema responde 201 Created
    Y se crea el registro en app.users
    Y se crea el registro en app.user_balances
    Y se emite USER_REGISTERED a ElasticSearch
    Y se emite WELCOME_EMAIL_FAILED como warning a ElasticSearch
    Y el usuario puede iniciar sesión normalmente
```

### 11.2 Criterios no funcionales verificables

| Criterio | Medida | Cómo se verifica |
|---|---|---|
| El endpoint responde en menos de 1s p95 | <1000ms p95 con 50 usuarios concurrentes | Test JMeter ad-hoc o medición manual con `time curl` |
| El password hasheado nunca aparece en logs | Sin ocurrencias de `password_hash` ni passwords plaintext en logs de aplicación o de auditoría | Inspección manual en Kibana después de varios registros |
| El password en BD no es plaintext | El campo `password_hash` empieza con `$2a$12$` (formato BCrypt cost 12) | `SELECT password_hash FROM app.users LIMIT 1;` |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/register`

**Propósito:** Formulario para que un usuario nuevo cree su cuenta en BloomTrade.

**Acceso:** Pública. Si el usuario ya tiene sesión activa, redirige a `/dashboard`.

**Componente principal:** `RegisterPage.tsx`

**Elementos visibles:**

| Elemento | Tipo | Comportamiento |
|---|---|---|
| Título "Crear cuenta en BloomTrade" | Heading | Estático |
| Campo Email | Input email | Required, validación on blur (formato email) |
| Campo Password | Input password con toggle de visibilidad | Required, validación on blur, indicador visual de fortaleza (débil/media/fuerte) |
| Campo Nombre completo | Input text | Required, 3-100 chars |
| Selector Tipo de documento | Select | Required, opciones CC / CE / Pasaporte |
| Campo Número de documento | Input text | Required, validación según tipo seleccionado |
| Campo Teléfono | Input tel con prefijo país | Required, formato E.164. Default prefijo: +57 |
| Checkbox "Acepto términos y condiciones" | Checkbox con link a `/terms` | Required, debe estar marcado para habilitar submit |
| Botón "Crear mi cuenta" | Submit button | Habilitado solo cuando todos los campos son válidos y términos aceptados. Muestra spinner durante submit. |
| Link "¿Ya tienes cuenta? Inicia sesión" | Link | Navega a `/login` |

**Estados de la página:**

| Estado | Trigger | UI resultante |
|---|---|---|
| Idle | Carga inicial de la página | Formulario vacío, botón submit deshabilitado |
| Validating | Usuario completando campos | Mensajes de validación inline en cada campo según se vayan validando |
| Submitting | Usuario presiona submit | Botón con spinner "Creando cuenta...", todos los inputs deshabilitados |
| Success | Backend responde 201 | Toast verde "Cuenta creada exitosamente. Por favor inicia sesión." → redirección automática a `/login` después de 1.5s |
| Error 409 | Email duplicado | Banner rojo arriba: "Este correo ya está registrado." con link a `/login`. Formulario habilitado para corrección. |
| Error 400 | Validación falló server-side | Errores específicos en los campos correspondientes. Banner amarillo si hay errores no asociados a un input. |
| Error 500 | Error técnico | Banner rojo "Error temporal del servidor. Por favor intenta de nuevo en unos momentos." Formulario habilitado. |

### 12.2 Componentes nuevos a crear

| Componente | Ubicación | Propósito |
|---|---|---|
| `RegisterPage` | `src/pages/RegisterPage.tsx` | Página completa con formulario |
| `RegisterForm` | `src/features/auth/components/RegisterForm.tsx` | Formulario controlado con react-hook-form + zod |
| `PasswordStrengthIndicator` | `src/components/PasswordStrengthIndicator.tsx` | Indicador visual de fortaleza (débil/media/fuerte). Reutilizable. |
| `PhoneInput` | `src/components/PhoneInput.tsx` | Input con selector de prefijo país. Reutilizable. |
| `TermsCheckbox` | `src/features/auth/components/TermsCheckbox.tsx` | Checkbox con link a /terms |
| `TermsPage` | `src/pages/TermsPage.tsx` | Página estática placeholder de términos |

### 12.3 Hooks o utilidades nuevas

| Item | Ubicación | Propósito |
|---|---|---|
| `useRegister` | `src/features/auth/hooks/useRegister.ts` | Mutación React Query para POST /auth/register |
| `registerSchema` | `src/features/auth/schemas/register.ts` | Zod schema de validación del formulario |
| `apiClient` | `src/lib/apiClient.ts` | Cliente axios base (introducido aquí, reutilizado en todas las features siguientes) |
| `errorParser` | `src/lib/errorParser.ts` | Utilidad para mapear `ErrorResponse` del backend a estado UI (introducido aquí, reutilizado) |

### 12.4 Cambios de routing

| Ruta | Componente | Acceso |
|---|---|---|
| `/register` | `RegisterPage` | Pública (redirige a `/dashboard` si hay sesión) |
| `/terms` | `TermsPage` | Pública. Página estática con texto "Términos y condiciones de BloomTrade — pendientes de definición legal." |

---

## 13. Fuera de alcance de esta spec

- **Verificación de email vía link** — auto-verify en MVP (ROADMAP §3.1)
- **Recuperación de contraseña** — diferida (HU-F08, post-MVP)
- **Captcha o anti-bot** — diferido a post-MVP
- **Rate limiting de registros** — diferido a post-MVP
- **Lista de passwords filtrados (HaveIBeenPwned)** — fuera de MVP por simplicidad
- **Selección de comisionista durante registro** — diferida (HU-F05, post-MVP)
- **Activación de plan premium durante registro** — diferida (HU-F06, parte del MVP pero spec separada)
- **Configuración inicial de canal de notificación** — diferida (HU-F20, parte del MVP pero spec separada)
- **Edición/actualización de datos del perfil después del registro** — diferida (HU-F04, parte del MVP pero spec separada)
- **Términos y condiciones legales reales** — el contenido de `/terms` es placeholder; el contenido legal real lo definiría el área legal de BloomTrade en producción
- **Creación de cuenta Alpaca por usuario** — fuera del MVP por decisión de `STACK.md` §7.1 (cuenta paper compartida)

---

## 14. Preguntas abiertas

| # | Pregunta | Owner | Fecha de resolución | Estado |
|---|---|---|---|---|
| 1 | ¿La creación del balance inicial debe ser responsabilidad de `AuthService` o de `PortfolioService`? | *[Tu nombre]* | 2026-05-08 | **Resolved** — Se acepta el compromiso pragmático: `AuthService` crea el balance dentro de su transacción invocando `BalanceInitializer` (componente del paquete `portfolio/`). Detalles, justificación y plan de refactor post-MVP documentados en §8.5. Acción derivada: actualizar `ARCHITECTURE.md` §5 para reflejar la nueva dependencia. |

---

## 15. Definition of Done específica de esta spec

- ☐ Migración Flyway `V2__auth_users_and_balances.sql` creada y aplicada exitosamente
- ☐ Endpoint `POST /api/v1/auth/register` documentado en Swagger UI con todos los códigos de respuesta
- ☐ Todos los escenarios Gherkin de §11 traducidos a tests automatizados pasando (unitarios + integración con Testcontainers)
- ☐ Eventos `USER_REGISTERED`, `USER_REGISTRATION_FAILED`, `WELCOME_EMAIL_FAILED` verificables en Kibana después de ejecutar el flujo
- ☐ Email de bienvenida visible en MailHog UI (`http://localhost:8025`) después de un registro exitoso
- ☐ Plantilla de email `welcome.html` creada en `backend/src/main/resources/templates/email/`
- ☐ Página `/register` accesible y funcional en `http://localhost:5173/register`
- ☐ Página `/terms` accesible (placeholder)
- ☐ Password almacenado en BD aparece como hash BCrypt (60 chars empezando con `$2a$12$`)
- ☐ Saldo inicial USD 10,000 verificable consultando `app.user_balances` después de registro
- ☐ El usuario registrado puede proceder a HU-F02 con sus credenciales (verificación manual)
- ☐ `apiClient` y `errorParser` creados como utilidades reutilizables en frontend

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-08 | Versión inicial | Primera spec del MVP |
| 1.1 | 2026-05-08 | Resuelta pregunta abierta #1 sobre responsabilidad de creación del balance inicial. (Anunciada §8.5, agregada de verdad en v1.2.) | Decisión tomada: AuthService crea el balance vía BalanceInitializer en misma transacción. Refactor a event-driven queda como post-MVP. |
| 1.2 | 2026-05-19 | Cierre HU-F01: agrega §8.5 (detalle de la desviación arquitectónica AuthService→PortfolioService.BalanceInitializer que el changelog v1.1 anunciaba sin redactar) + §10 nueva (Riesgos y mitigaciones, R1–R6 trazables a tests). Corrige ejemplo Gherkin de §11 `"+57"`→201 alineado al regex del contrato §6.1 (resuelve inconsistencia interna, decisión D15 del plan.md). | Cierre del trabajo de completitud de SPEC pendiente del plan.md Q1; mantenimiento de la trazabilidad SDD (los SPECs son la principal evidencia académica). |
