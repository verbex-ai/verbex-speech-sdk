"""Speech-to-text models: what each transcribes and the modes it serves."""
from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, List, Mapping, Optional

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech


@dataclass(frozen=True)
class Feature:
    param_name: str
    display_name: Optional[str]
    type: Optional[str]
    max_items: Optional[int]
    description: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Feature":
        return cls(d.get("param_name", ""), d.get("display_name"), d.get("type"), d.get("max_items"),
                   d.get("description"))


@dataclass(frozen=True)
class ModelMode:
    mode: str
    features: List[Feature]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "ModelMode":
        return cls(d.get("mode", ""), [Feature.from_dict(f) for f in d.get("features") or []])


@dataclass(frozen=True)
class ListenModel:
    """A speech-to-text model: the one language it transcribes and the modes it serves."""

    model: str
    display_name: Optional[str]
    language: str
    modes: List[ModelMode]

    def serves(self, mode: str) -> bool:
        """``async`` for file jobs, ``stream`` for live sessions."""
        return any(m.mode == mode for m in self.modes)

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "ListenModel":
        return cls(d.get("model", ""), d.get("display_name"), d.get("language", ""),
                   [ModelMode.from_dict(m) for m in d.get("modes") or []])


def _models(answer: Mapping[str, Any]) -> List[ListenModel]:
    return [ListenModel.from_dict(m) for m in answer.get("items") or []]


def list_models(client: "VerbexSpeech") -> List[ListenModel]:
    return _models(client._request("get", "GET", "/v1/listen/models").json())


async def list_models_async(client: "AsyncVerbexSpeech") -> List[ListenModel]:
    return _models((await client._request("get", "GET", "/v1/listen/models")).json())
