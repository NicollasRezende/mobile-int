import logging
import socket
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.config import get_settings
from app.database import init_db
from app.routers import cards, scans, write, ws
from app.services.hub import hub

settings = get_settings()
logging.basicConfig(
    level=settings.log_level.upper(),
    format="%(asctime)s %(levelname)-7s %(name)s | %(message)s",
)
logger = logging.getLogger("rfid")

STATIC_DIR = Path(__file__).parent / "static"


def local_ips() -> list[str]:
    """IPs que o celular pode usar para achar o PC na rede."""
    ips: set[str] = set()
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))  # não envia nada, só resolve a rota de saída
        ips.add(sock.getsockname()[0])
        sock.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    for ip in local_ips():
        logger.info("app Android → ws://%s:8000/ws/device  |  painel → http://%s:8000", ip, ip)
    yield


app = FastAPI(title=settings.app_name, version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # rede local; restrinja se expuser para fora
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(ws.router)
app.include_router(scans.router)
app.include_router(cards.router)
app.include_router(write.router)


@app.get("/api/health")
def health() -> dict:
    return {
        "status": "ok",
        "app": settings.app_name,
        "devices": hub.device_names(),
        "dashboards": len(hub.dashboards),
        "local_ips": local_ips(),
        "auto_register_cards": settings.auto_register_cards,
    }


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/", include_in_schema=False)
def dashboard() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")
