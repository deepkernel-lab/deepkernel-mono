# Photo Gallery Demo

A simple photo gallery demo app.

## Services
- `backend` (Node.js/Express): serves image metadata.
- `frontend` (React): displays the photos.

## Run (compose)
```bash
cd demo-apps/photo-gallery
docker-compose up -d --build
docker-compose logs -f
```

Frontend: http://localhost:3001
Backend: http://localhost:8001
