from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.database import get_session
from app.repositories import cards as cards_repo
from app.schemas import CardIn, CardOut, CardPatch
from app.services.rfid import normalize_uid

router = APIRouter(prefix="/api/cards", tags=["cards"])


@router.get("", response_model=list[CardOut])
def list_cards(session: Session = Depends(get_session)) -> list[CardOut]:
    return [CardOut.model_validate(c) for c in cards_repo.list_cards(session)]


@router.post("", response_model=CardOut, status_code=201)
def create_card(payload: CardIn, session: Session = Depends(get_session)) -> CardOut:
    uid = normalize_uid(payload.uid)
    if not uid:
        raise HTTPException(status_code=422, detail="uid inválido")
    if cards_repo.get_by_uid(session, uid):
        raise HTTPException(status_code=409, detail="uid já cadastrado")
    card = cards_repo.create(
        session,
        uid,
        label=payload.label,
        authorized=payload.authorized,
        action=payload.action,
        notes=payload.notes,
    )
    session.commit()
    return CardOut.model_validate(card)


@router.patch("/{uid}", response_model=CardOut)
def update_card(
    uid: str, payload: CardPatch, session: Session = Depends(get_session)
) -> CardOut:
    card = cards_repo.get_by_uid(session, normalize_uid(uid))
    if card is None:
        raise HTTPException(status_code=404, detail="cartão não encontrado")
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(card, field, value)
    session.commit()
    return CardOut.model_validate(card)


@router.delete("/{uid}", status_code=204)
def delete_card(uid: str, session: Session = Depends(get_session)) -> None:
    card = cards_repo.get_by_uid(session, normalize_uid(uid))
    if card is None:
        raise HTTPException(status_code=404, detail="cartão não encontrado")
    cards_repo.delete(session, card)
    session.commit()
