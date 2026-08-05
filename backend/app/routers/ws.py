import json
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool

from app.database import SessionLocal
from app.schemas import ScanIn
from app.services import rfid
from app.services.auth import token_is_valid
from app.services.hub import hub

logger = logging.getLogger(__name__)
router = APIRouter()


def _handle_scan(payload: ScanIn, device: str):
    """Roda em threadpool: SQLAlchemy síncrono não pode bloquear o event loop."""
    session = SessionLocal()
    try:
        scan, result = rfid.process_scan(
            session, payload, device_fallback=device, source="ws"
        )
        return rfid.scan_event(scan), result
    finally:
        session.close()


@router.websocket("/ws/device")
async def device_socket(
    websocket: WebSocket,
    token: str | None = Query(default=None),
    device: str = Query(default="celular"),
) -> None:
    if not token_is_valid(token):
        await websocket.close(code=4401, reason="token inválido")
        logger.warning("device recusado (token inválido): %s", device)
        return

    await websocket.accept()
    await hub.join_device(websocket, device)
    await websocket.send_json(
        {
            "type": "hello",
            "device": device,
            "server_time": datetime.now(timezone.utc).isoformat(),
            "message": "conectado",
        }
    )
    logger.info("device conectado: %s", device)

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json(
                    {"type": "error", "status": "error", "message": "JSON inválido"}
                )
                continue

            msg_type = data.get("type", "scan")

            if msg_type == "ping":
                await websocket.send_json(
                    {"type": "pong", "server_time": datetime.now(timezone.utc).isoformat()}
                )
                continue

            if msg_type not in ("scan", "dump"):
                await websocket.send_json(
                    {
                        "type": "error",
                        "status": "error",
                        "message": f"tipo desconhecido: {msg_type}",
                    }
                )
                continue

            try:
                payload = ScanIn.model_validate(data)
            except ValidationError as exc:
                await websocket.send_json(
                    {
                        "type": "error",
                        "status": "error",
                        "id": data.get("id"),
                        "message": "payload inválido",
                        "detail": exc.errors(include_url=False)[:5],
                    }
                )
                continue

            event, result = await run_in_threadpool(_handle_scan, payload, device)
            await websocket.send_json(result.model_dump(mode="json"))
            await hub.broadcast_dashboards(event)
            logger.info(
                "scan %s uid=%s tipo=%s autorizado=%s",
                event["scan"]["id"],
                event["scan"]["uid"],
                event["scan"]["tag_type"],
                event["scan"]["authorized"],
            )
    except WebSocketDisconnect:
        logger.info("device desconectado: %s", device)
    except Exception:
        logger.exception("erro no socket do device %s", device)
    finally:
        await hub.leave_device(websocket)


@router.websocket("/ws/dashboard")
async def dashboard_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    await hub.join_dashboard(websocket)
    await websocket.send_json({"type": "hello", "devices": hub.device_names()})
    try:
        while True:
            # O dashboard só escuta; qualquer texto recebido serve de keepalive.
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("erro no socket do dashboard")
    finally:
        await hub.leave_dashboard(websocket)
