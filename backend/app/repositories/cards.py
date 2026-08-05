from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Card


def get_by_uid(session: Session, uid: str) -> Card | None:
    return session.scalar(select(Card).where(Card.uid == uid))


def list_cards(session: Session, limit: int = 200) -> list[Card]:
    stmt = select(Card).order_by(Card.last_seen_at.desc().nullslast()).limit(limit)
    return list(session.scalars(stmt))


def create(
    session: Session,
    uid: str,
    *,
    label: str | None = None,
    authorized: bool = False,
    action: str | None = None,
    notes: str | None = None,
    tag_type: str | None = None,
) -> Card:
    card = Card(
        uid=uid,
        label=label,
        authorized=authorized,
        action=action,
        notes=notes,
        tag_type=tag_type,
    )
    session.add(card)
    session.flush()
    return card


def touch(session: Session, card: Card, seen_at: datetime, tag_type: str | None) -> Card:
    card.scan_count += 1
    card.last_seen_at = seen_at
    if tag_type and not card.tag_type:
        card.tag_type = tag_type
    session.flush()
    return card


def delete(session: Session, card: Card) -> None:
    session.delete(card)
