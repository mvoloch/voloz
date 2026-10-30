"""User settings endpoints — capture/refine and generation defaults."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

import logging

from .. import models
from ..database import get_db
from ..services import audio_retention, quick_setup
from ..services import settings as settings_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/settings", tags=["settings"])


@router.get("/captures", response_model=models.CaptureSettingsResponse)
async def get_capture_settings_endpoint(db: Session = Depends(get_db)):
    return settings_service.get_capture_settings(db)


@router.put("/captures", response_model=models.CaptureSettingsResponse)
async def update_capture_settings_endpoint(
    patch: models.CaptureSettingsUpdate,
    db: Session = Depends(get_db),
):
    try:
        row = settings_service.update_capture_settings(db, patch.model_dump(exclude_unset=True))
    except settings_service.SettingsError as e:
        raise HTTPException(status_code=400, detail=str(e))
    if patch.audio_retention is not None:
        # A shorter retention applies at once, not at the next capture.
        try:
            audio_retention.purge_expired_audio(db, row.audio_retention)
        except Exception:
            logger.exception("Audio retention failed after a settings change")
    return row


@router.post("/captures/quick-setup", response_model=models.CaptureSettingsResponse)
async def quick_setup_endpoint(request: models.QuickSetupRequest, db: Session = Depends(get_db)):
    """Validate a Groq key (and optionally an Anthropic key) and configure dictation.

    The first-run screen of the lite edition calls this. Transcription goes
    to Groq; refinement to Claude when an Anthropic key is given, else to
    Groq. Errors carry ``detail = {"message", "field"}`` naming the key at
    fault: 400 when the provider rejected it, 503 when it could not be
    reached. Nothing is stored on failure.
    """
    try:
        return await quick_setup.configure_quick_setup(
            db, request.groq_api_key, request.anthropic_api_key
        )
    except quick_setup.QuickSetupError as e:
        raise HTTPException(
            status_code=e.status_code, detail={"message": str(e), "field": e.field}
        )


@router.get("/generation", response_model=models.GenerationSettingsResponse)
async def get_generation_settings_endpoint(db: Session = Depends(get_db)):
    return settings_service.get_generation_settings(db)


@router.put("/generation", response_model=models.GenerationSettingsResponse)
async def update_generation_settings_endpoint(
    patch: models.GenerationSettingsUpdate,
    db: Session = Depends(get_db),
):
    return settings_service.update_generation_settings(db, patch.model_dump(exclude_unset=True))
