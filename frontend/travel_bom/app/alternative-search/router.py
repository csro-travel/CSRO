from fastapi import APIRouter

from .main import router as alternative_search_router

api_router = APIRouter()
api_router.include_router(alternative_search_router)
