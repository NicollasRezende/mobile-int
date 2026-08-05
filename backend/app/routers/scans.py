from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.database import get_session
from app.repositories import scans as scans_repo
from app.schemas import ScanIn, ScanOut, ScanResult, ScanSummary
from app.services import rfid
from app.services.auth import require_device_token
from app.services.hub import hub

router = APIRouter(prefix="/api", tags=["scans"])


@router.post("/scans", response_model=ScanResult)
async def ingest_scan(
    payload: ScanIn,
    session: Session = Depends(get_session),
    _: str = Depends(require_device_token),
) -> ScanResult:
    """Mesma ingestão do WebSocket, via HTTP — útil para curl e para o app
    quando o socket estiver caído."""
    scan, result = rfid.process_scan(session, payload, source="http")
    await hub.broadcast_dashboards(rfid.scan_event(scan))
    return result


@router.get("/scans", response_model=list[ScanSummary])
def list_scans(
    limit: int = Query(default=50, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    uid: str | None = None,
    session: Session = Depends(get_session),
) -> list[ScanSummary]:
    normalized = rfid.normalize_uid(uid) if uid else None
    rows = scans_repo.list_scans(session, limit=limit, offset=offset, uid=normalized)
    return [ScanSummary.model_validate(row) for row in rows]


@router.get("/scans/{scan_id}", response_model=ScanOut)
def get_scan(scan_id: int, session: Session = Depends(get_session)) -> ScanOut:
    scan = scans_repo.get(session, scan_id)
    if scan is None:
        raise HTTPException(status_code=404, detail="scan não encontrado")
    return ScanOut.model_validate(scan)


@router.delete("/scans")
def clear_scans(session: Session = Depends(get_session)) -> dict[str, int]:
    removed = scans_repo.clear(session)
    session.commit()
    return {"removed": removed}
