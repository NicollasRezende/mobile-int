from datetime import datetime, timezone

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Card(Base):
    """Cartão conhecido. Enquanto AUTO_REGISTER_CARDS estiver ligado, todo UID novo
    entra aqui automaticamente como não autorizado — é só dar nome depois."""

    __tablename__ = "cards"

    id: Mapped[int] = mapped_column(primary_key=True)
    uid: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    label: Mapped[str | None] = mapped_column(String(120), default=None)
    authorized: Mapped[bool] = mapped_column(Boolean, default=False)
    action: Mapped[str | None] = mapped_column(String(64), default=None)
    notes: Mapped[str | None] = mapped_column(Text, default=None)
    tag_type: Mapped[str | None] = mapped_column(String(120), default=None)
    scan_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
    last_seen_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), default=None
    )

    scans: Mapped[list["Scan"]] = relationship(back_populates="card")


class Scan(Base):
    """Cada aproximação de tag. O dump bruto vai inteiro em `dump`."""

    __tablename__ = "scans"

    id: Mapped[int] = mapped_column(primary_key=True)
    uid: Mapped[str] = mapped_column(String(64), index=True)
    device: Mapped[str | None] = mapped_column(String(120), default=None)
    tag_type: Mapped[str | None] = mapped_column(String(120), default=None)
    tech_list: Mapped[str | None] = mapped_column(Text, default=None)
    authorized: Mapped[bool] = mapped_column(Boolean, default=False)
    known: Mapped[bool] = mapped_column(Boolean, default=False)
    source: Mapped[str] = mapped_column(String(16), default="ws")
    read_ms: Mapped[int | None] = mapped_column(Integer, default=None)
    scanned_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), default=None
    )
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, index=True
    )
    dump: Mapped[dict] = mapped_column(JSON, default=dict)

    card_id: Mapped[int | None] = mapped_column(ForeignKey("cards.id"), default=None)
    card: Mapped[Card | None] = relationship(back_populates="scans")
