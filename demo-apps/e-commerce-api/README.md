# E-commerce API Demo

A simple e-commerce API demo app.

## Services
- `backend` (Python/Flask): provides product and order endpoints.

## Run (compose)
```bash
cd demo-apps/e-commerce-api
docker-compose up -d --build
docker-compose logs -f
```

API available at http://localhost:8002
- `GET /products`
- `GET /orders`
