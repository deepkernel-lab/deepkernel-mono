from fastapi import FastAPI

app = FastAPI(title="DeepKernel ML Service", version="0.1.0")


@app.get("/health")
async def health():
    return {"status": "ok", "message": "ML service skeleton"}


# TODO: add endpoints for scoring and training Isolation Forest models.


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8081)

