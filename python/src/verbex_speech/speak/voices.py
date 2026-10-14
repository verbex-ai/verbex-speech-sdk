"""Text-to-speech voices: the voices of each model a workspace can use."""
from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, List, Mapping, Optional

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech


@dataclass(frozen=True)
class Voice:
    model: str
    model_display_name: Optional[str]
    code: str
    display_name: Optional[str]
    language: Optional[str]
    gender: Optional[str]
    style: Optional[str]
    sample_url: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Voice":
        return cls(d.get("model", ""), d.get("model_display_name"), d.get("code", ""), d.get("display_name"),
                   d.get("language"), d.get("gender"), d.get("style"), d.get("sample_url"))


def _voices(answer: Mapping[str, Any]) -> List[Voice]:
    return [Voice.from_dict(v) for v in answer.get("items") or []]


def list_voices(client: "VerbexSpeech") -> List[Voice]:
    return _voices(client._request("get", "GET", "/v1/speak/voices").json())


async def list_voices_async(client: "AsyncVerbexSpeech") -> List[Voice]:
    return _voices((await client._request("get", "GET", "/v1/speak/voices")).json())
