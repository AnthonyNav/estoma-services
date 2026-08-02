# communications-service

Spring Boot service that manages platform communications and delivery history.

## Owns

- Emails.
- Templates.
- Internal inbox.
- Informational notifications.
- Delivery retries.
- Email providers.
- Delivery history.

## Does Not Own

- Business decisions that trigger messages.
- Identity, authentication, or authorization data.

## Initial Endpoints

- `GET /health`
- `GET /version`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
