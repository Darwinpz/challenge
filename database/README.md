# Database Scripts - Assessment

## Descripción

Este directorio contiene los scripts SQL para inicializar las bases de datos de PostgreSQL utilizadas en el proyecto de microservicios.

## Estructura

```
database/
├── init-customer-db.sql    # Schema para customer-service (clientes y personas)
├── init-account-db.sql     # Schema para account-service (cuentas y movimientos)
└── README.md               # Este archivo
```

---

## 📊 Schema: customer_db

### Tablas principales:

1. **persona**
   - Información base de personas
   - Campos: name, gender, age, identification, address, phone
   - Índices: identification (UNIQUE), name

2. **cliente**
   - Información de clientes con credenciales
   - Campos: customer_id (FK a persona), password (BCrypt), state, version
   - Optimistic Locking implementado con campo `version`

**Event Publishing:**
   - Events are published **directly to Kafka** from the application layer (no event table)
   - Kafka Topics: `customer.created`, `customer.updated`, `customer.deleted`

### Características Senior:
- ✅ Optimistic Locking para concurrencia
- ✅ Event-Driven Architecture con Kafka (publicación directa)
- ✅ Triggers automáticos para `updated_at`
- ✅ Vista desnormalizada `customer_full_view`
- ✅ Datos de prueba (seed data)

---

## 📊 Schema: account_db

### Tablas principales:

1. **cuenta**
   - Cuentas bancarias
   - Tipos: AHORRO, CORRIENTE
   - Campos: account_number (PK), account_type, balance, customer_id, customer_name (desnormalizado)

2. **movimiento**
   - Movimientos transaccionales
   - Tipos: DEBITO, CREDITO, REVERSA
   - Campos: movement_id, account_number (FK), amount, balance_before, balance_after, transaction_id (UNIQUE)
   - Idempotency implementado con `idempotency_key`

**Customer Validation:**
   - Customer validation via HTTP call to `customer-service`
   - If caching needed: Use Redis or Caffeine (in-memory), not PostgreSQL

**Event Publishing:**
   - Events are published **directly to Kafka** from the application layer (no event table)
   - Kafka Topics (3 topics):
     - `customer.events` - Customer lifecycle events (created, updated, deleted)
     - `account.events` - Account lifecycle events (created, updated, deleted)
     - `movement.events` - Movement/transaction events (created, reversed) - **High frequency**

### Características Senior:
- ✅ Optimistic Locking en tabla cuenta
- ✅ Triggers para validación de saldo (antes de INSERT)
- ✅ Triggers para actualización automática de saldo (después de INSERT)
- ✅ Función para generar números de cuenta aleatorios
- ✅ Idempotency Key para prevenir duplicados
- ✅ Transaction ID único para trazabilidad
- ✅ Vista agregada `account_summary`
- ✅ Validación de "Saldo no disponible" a nivel de BD
- ✅ Customer validation via HTTP (no DB cache)

---

## 🐳 Uso con Docker Compose

### Levantar la infraestructura completa:

```bash
# Desde la raíz del proyecto
docker-compose up -d
```

### Servicios disponibles:

| Servicio | Puerto | Descripción |
|----------|--------|-------------|
| customer-db | 5432 | PostgreSQL - Base de datos de clientes |
| account-db | 5433 | PostgreSQL - Base de datos de cuentas |
| kafka | 29092 | Apache Kafka - Message Broker |
| kafka-ui | 8080 | Interfaz web para Kafka |
| zookeeper | 2181 | Zookeeper (requerido por Kafka) |
| pgadmin | 5050 | Administrador web de PostgreSQL |

### Credenciales:

**customer-db:**
- Database: `customer_db`
- User: `customer_user`
- Password: `customer_pass`
- Host: `localhost`
- Port: `5432`

**account-db:**
- Database: `account_db`
- User: `account_user`
- Password: `account_pass`
- Host: `localhost`
- Port: `5433`

---

## 🔄 Reiniciar bases de datos (sin persistencia)

Para reiniciar con datos limpios:

```bash
# Detener y eliminar contenedores
docker-compose down -v

# Levantar nuevamente (ejecutará los scripts de inicialización)
docker-compose up -d
```

---

## 🧪 Verificar que las bases de datos están funcionando

### Conectar a customer-db:
```bash
docker exec -it customer-db psql -U customer_user -d customer_db
```

### Queries de prueba:
```sql
-- Ver clientes
SELECT * FROM customer_full_view;

-- Ver personas
SELECT * FROM persona;

-- Check customer versions (for optimistic locking)
SELECT customer_id, name, version FROM customer_full_view;
```

### Conectar a account-db:
```bash
docker exec -it account-db psql -U account_user -d account_db
```

### Queries de prueba:
```sql
-- Ver cuentas con resumen
SELECT * FROM account_summary;

-- Ver movimientos
SELECT * FROM movimiento ORDER BY created_at DESC;

-- Ver saldo de una cuenta específica
SELECT account_number, balance FROM cuenta WHERE account_number = 478758;

```

---

## 📝 Datos de prueba (Seed Data)

### Clientes precargados:

1. **Jose Lema** (UUID: `550e8400-e29b-41d4-a716-446655440000`)
   - Identificación: 0705463420
   - Cuenta AHORRO: 478758 (Saldo: $2000.00)

2. **Marianela Montalvo** (UUID: `660e8400-e29b-41d4-a716-446655440001`)
   - Identificación: 0705463421
   - Cuenta CORRIENTE: 225487 (Saldo: $100.00)
   - Cuenta AHORRO: 496825 (Saldo: $0.00)

3. **Juan Osorio** (UUID: `770e8400-e29b-41d4-a716-446655440002`)
   - Identificación: 0705463422
   - Cuenta CORRIENTE: 585545 (Saldo: $1000.00)
   - Cuenta AHORRO: 588923 (Saldo: $0.00, estado: inactiva)

---

## 🔧 Funciones útiles

### Generar número de cuenta aleatorio:
```sql
SELECT generate_account_number();
```

### Limpiar cache de clientes expirado:
```sql
SELECT cleanup_expired_cache();
```

---

## 🏗️ Características de arquitectura Senior implementadas

### 1. **Transacciones ACID**
   - Garantizadas por PostgreSQL
   - Triggers para mantener integridad referencial

### 2. **Optimistic Locking**
   - Campo `version` en tablas `cliente` y `cuenta`
   - Se incrementa automáticamente en cada UPDATE

### 3. **Event-Driven Architecture**
   - Eventos publicados directamente a Kafka desde la capa de aplicación
   - Sin necesidad de tablas intermedias (Transactional Outbox eliminado)
   - Publicación reactiva e inmediata

### 4. **Idempotency**
   - Campo `idempotency_key` en tabla `movimiento`
   - Previene duplicación de transacciones

### 5. **Desnormalización controlada**
   - Campo `customer_name` en tabla `cuenta`
   - Mejora performance en consultas frecuentes

### 6. **Validaciones a nivel de base de datos**
   - CHECK constraints para tipos y rangos
   - Triggers para validar saldo antes de débitos
   - Validación: "Saldo no disponible" → EXCEPTION

### 7. **Trazabilidad completa**
   - `transaction_id` único por movimiento
   - `correlation_id` y `request_id` para seguimiento end-to-end

---

## 📚 Referencias

- [PostgreSQL Triggers](https://www.postgresql.org/docs/current/triggers.html)
- [Optimistic Locking Pattern](https://martinfowler.com/eaaCatalog/optimisticOfflineLock.html)
- [Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Idempotency Patterns](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)

---

**Autor:** Darwin Pilaloa Zea
**Fecha:** 2025-11-07
**Proyecto:** Technical Assessment - Senior Level
