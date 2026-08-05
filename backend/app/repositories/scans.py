from sqlalchemy import delete as sa_delete
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import Scan


def add(session: Session, scan: Scan) -> Scan:
    session.add(scan)
    session.flush()
    return scan


def get(session: Session, scan_id: int) -> Scan | None:
    return session.get(Scan, scan_id)


def list_scans(
    session: Session, *, limit: int = 50, offset: int = 0, uid: str | None = None
) -> list[Scan]:
    stmt = select(Scan).order_by(Scan.id.desc()).limit(limit).offset(offset)
    if uid:
        stmt = stmt.where(Scan.uid == uid)
    return list(session.scalars(stmt))


def count(session: Session, uid: str | None = None) -> int:
    stmt = select(func.count(Scan.id))
    if uid:
        stmt = stmt.where(Scan.uid == uid)
    return session.scalar(stmt) or 0


def clear(session: Session) -> int:
    total = count(session)
    session.execute(sa_delete(Scan))
    return total
