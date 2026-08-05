import asyncio
import logging
from typing import Any

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class Hub:
    """Guarda as conexões vivas: celulares (devices) e navegadores (dashboards)."""

    def __init__(self) -> None:
        self.devices: dict[WebSocket, str] = {}
        self.dashboards: set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def join_device(self, ws: WebSocket, name: str) -> None:
        async with self._lock:
            self.devices[ws] = name
        await self.broadcast_dashboards(
            {"type": "device_connected", "device": name, "devices": self.device_names()}
        )

    async def leave_device(self, ws: WebSocket) -> None:
        async with self._lock:
            name = self.devices.pop(ws, None)
        if name:
            await self.broadcast_dashboards(
                {
                    "type": "device_disconnected",
                    "device": name,
                    "devices": self.device_names(),
                }
            )

    async def join_dashboard(self, ws: WebSocket) -> None:
        async with self._lock:
            self.dashboards.add(ws)

    async def leave_dashboard(self, ws: WebSocket) -> None:
        async with self._lock:
            self.dashboards.discard(ws)

    def device_names(self) -> list[str]:
        return sorted(set(self.devices.values()))

    async def send_to_devices(self, message: dict[str, Any]) -> list[str]:
        """Manda um comando para os celulares. Devolve quem recebeu."""
        entregues: list[str] = []
        dead: list[WebSocket] = []
        for ws, name in list(self.devices.items()):
            try:
                await ws.send_json(message)
                entregues.append(name)
            except Exception:
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    self.devices.pop(ws, None)
        return entregues

    async def broadcast_dashboards(self, message: dict[str, Any]) -> None:
        if not self.dashboards:
            return
        dead: list[WebSocket] = []
        for ws in list(self.dashboards):
            try:
                await ws.send_json(message)
            except Exception:  # conexão morreu no meio do envio
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    self.dashboards.discard(ws)
            logger.info("removidos %d dashboards mortos", len(dead))


hub = Hub()
