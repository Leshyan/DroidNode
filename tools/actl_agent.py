#!/usr/bin/env python3
"""
ACTL LLM/VLM agent.

Behavior:
- Use screenshot UI state via /v1/ui/screenshot and call VLM only.
- Parse model action output and execute ACTL APIs.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


def _inject_local_venv_site_packages() -> None:
    script_dir = Path(__file__).resolve().parent
    venv_site = (
        script_dir / ".venv" / "lib" / f"python{sys.version_info.major}.{sys.version_info.minor}" / "site-packages"
    )
    if venv_site.is_dir() and str(venv_site) not in sys.path:
        sys.path.insert(0, str(venv_site))
        print(f"[env] loaded local venv site-packages: {venv_site}")


_inject_local_venv_site_packages()


def _now_cn_date() -> str:
    weekday = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    now = datetime.now()
    return f"{now:%Y-%m-%d} {weekday[now.weekday()]}"


SYSTEM_PROMPT = f"""
Today is {_now_cn_date()}.
You are a mobile control planning agent.
You MUST output exactly:
<think>short reasoning</think>
<answer>one action</answer>

Supported actions:
- do(action="Tap", element=[x,y])
- do(action="Swipe", start=[x1,y1], end=[x2,y2], durationMs=300)
- do(action="Type", text="xxx")
- do(action="Type", text="xxx", enterAction="send")
- do(action="Wait", duration="2 seconds")
- finish(message="xxx")

Rules:
1) Return exactly one action per step.
2) Prefer minimal steps.
3) If UI info is insufficient, use Wait or a cautious Tap.
4) Never output any action outside supported list.
5) Coordinates must stay in device click range.
6) Keep <think> very short (<= 20 words), then output <answer> immediately.
7) If repeated taps do not change UI, next tap must drift by at least 50px.
8) If same UI repeats 2+ times, avoid repeating identical Tap; choose a different control or Swipe.
9) Sometimes two screenshots are provided together: first is full screen, second is local focus crop around the previous ineffective tap.
10) The local focus crop contains grid lines and a center marker; use it to refine click coordinates.
11) If the local crop looked near-solid (all black/white), it may have been auto-expanded by +50px steps; trust the final crop bounds from prompt text.
""".strip()


STRICT_ACTION_PROMPT = """
You are a mobile control planner.
Output ONE line only, no explanation:
- do(action="Tap", element=[x,y])
- do(action="Swipe", start=[x1,y1], end=[x2,y2], durationMs=300)
- do(action="Type", text="xxx")
- do(action="Type", text="xxx", enterAction="send")
- do(action="Wait", duration="2 seconds")
- finish(message="xxx")
Do not output <think>.
If previous tap was ineffective, new tap should drift >= 50px.
If same UI repeats, do not repeat the identical tap.
If two images are provided, use full screenshot + local focus crop together.
The local focus crop has grid lines and click-center marker.
""".strip()


@dataclass
class UiState:
    source: str  # "xml" | "screenshot"
    text: str
    image_b64: str = ""
    state_sig: str = ""
    screen_ahash: int | None = None


@dataclass
class FocusContext:
    image_b64: str
    center_x: int
    center_y: int
    radius_px: int
    box_left: int
    box_top: int
    box_right: int
    box_bottom: int
    reason: str = ""


@dataclass
class ScreenshotFrame:
    png_bytes: bytes
    image_b64: str
    content_type: str


class HttpError(RuntimeError):
    pass


class ActlClient:
    def __init__(self, base_url: str, timeout_sec: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_sec = timeout_sec

    def _request_json(self, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, str]:
        url = f"{self.base_url}{path}"
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url=url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                return resp.status, resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read().decode("utf-8", errors="replace")
        except urllib.error.URLError as exc:
            raise HttpError(f"ACTL request failed: {exc}") from exc

    def _request_bytes(self, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, bytes, str]:
        url = f"{self.base_url}{path}"
        data = None
        headers = {"Accept": "*/*"}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url=url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                return resp.status, resp.read(), resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as exc:
            content_type = exc.headers.get("Content-Type", "") if exc.headers else ""
            return exc.code, exc.read(), content_type
        except urllib.error.URLError as exc:
            raise HttpError(f"ACTL request failed: {exc}") from exc

    def get_system_info(self) -> dict[str, Any]:
        status, body = self._request_json("GET", "/v1/system/info")
        if status != 200:
            raise HttpError(f"/v1/system/info failed: HTTP {status}, body={body}")
        return json.loads(body)

    def get_ui_xml(self) -> tuple[int, str]:
        return self._request_json("POST", "/v1/ui/xml", {})

    def get_ui_screenshot(self) -> ScreenshotFrame:
        status, body, content_type = self._request_bytes("POST", "/v1/ui/screenshot", {})
        if status != 200:
            preview = body.decode("utf-8", errors="replace")
            raise HttpError(f"/v1/ui/screenshot failed: HTTP {status}, body={preview}")
        if "image/png" not in content_type.lower():
            preview = body.decode("utf-8", errors="replace")
            raise HttpError(
                "/v1/ui/screenshot unexpected content type: "
                f"{content_type}, body={preview}"
            )
        image_b64 = base64.b64encode(body).decode("ascii")
        return ScreenshotFrame(
            png_bytes=body,
            image_b64=image_b64,
            content_type=content_type,
        )

    def click(self, x: int, y: int) -> tuple[bool, str]:
        status, body = self._request_json("POST", "/v1/control/click", {"x": x, "y": y})
        return status == 200, body

    def swipe(self, sx: int, sy: int, ex: int, ey: int, duration_ms: int) -> tuple[bool, str]:
        payload = {
            "startX": sx,
            "startY": sy,
            "endX": ex,
            "endY": ey,
            "durationMs": duration_ms,
        }
        status, body = self._request_json("POST", "/v1/control/swipe", payload)
        return status == 200, body

    def input_text(self, text: str, enter_action: str | None = None) -> tuple[bool, str]:
        payload: dict[str, Any] = {"text": text, "pressEnter": False, "enterAction": "auto"}
        if enter_action and enter_action.lower() != "none":
            payload["pressEnter"] = True
            payload["enterAction"] = enter_action
        status, body = self._request_json("POST", "/v1/control/input", payload)
        return status == 200, body


class OpenAICompatClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_sec: int,
        temperature: float | None,
        max_tokens: int | None,
        thinking: dict[str, Any] | bool | None = None,
        extra_body: dict[str, Any] | None = None,
    ):
        self.base_url = base_url
        self.api_key = api_key
        self.model = model
        self.timeout_sec = timeout_sec
        self.temperature = temperature
        self.max_tokens = max_tokens
        if isinstance(thinking, dict):
            self.thinking = thinking
        elif thinking is True:
            self.thinking = {"type": "enabled"}
        else:
            self.thinking = None
        self.extra_body = extra_body if isinstance(extra_body, dict) else {}
        self.last_finish_reason = ""

    def _chat(self, messages: list[dict[str, Any]]) -> str:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
        }
        if self.temperature is not None:
            payload["temperature"] = self.temperature
        if self.max_tokens is not None:
            payload["max_tokens"] = self.max_tokens
        if self.thinking is not None:
            payload["thinking"] = self.thinking
        if self.extra_body:
            payload.update(self.extra_body)
        req = urllib.request.Request(
            url=self.base_url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                body = resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise HttpError(f"LLM/VLM HTTP {exc.code}: {body}") from exc
        except urllib.error.URLError as exc:
            raise HttpError(f"LLM/VLM request failed: {exc}") from exc

        obj = json.loads(body)
        choices = obj.get("choices", [])
        first_choice = choices[0] if isinstance(choices, list) and choices else {}
        self.last_finish_reason = str(first_choice.get("finish_reason", ""))
        message = first_choice.get("message", {}) if isinstance(first_choice, dict) else {}
        choice = message.get("content", "")
        if isinstance(choice, list):
            texts = []
            for item in choice:
                if isinstance(item, dict):
                    item_type = str(item.get("type", ""))
                    if item_type in ("text", "output_text"):
                        text_value = item.get("text", item.get("content", ""))
                        texts.append(str(text_value))
            return "\n".join(texts).strip()
        return str(choice).strip()

    def chat_text(self, user_text: str) -> str:
        return self._chat(
            [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_text},
            ]
        )

    def chat_with_images(self, user_text: str, images_b64: list[str]) -> str:
        content: list[dict[str, Any]] = [{"type": "text", "text": user_text}]
        for img_b64 in images_b64:
            content.append({"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img_b64}"}})
        return self._chat(
            [
                {"role": "system", "content": SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": content,
                },
            ]
        )


def _xml_is_valid(xml_text: str) -> bool:
    text = xml_text.strip()
    if "<?xml" not in text and "<hierarchy" not in text:
        return False
    try:
        ET.fromstring(text)
        return True
    except ET.ParseError:
        return False


def _xml_is_useful(xml_text: str) -> bool:
    """Return False when XML is structurally valid but effectively empty."""
    if not _xml_is_valid(xml_text):
        return False
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return False

    nodes = [n for n in root.iter() if n.tag == "node"]
    if not nodes:
        return False

    # A lot of "empty" dumps contain only a placeholder root node.
    if len(nodes) <= 1:
        node = nodes[0]
        text = (node.attrib.get("text") or "").strip()
        rid = (node.attrib.get("resource-id") or "").strip()
        desc = (node.attrib.get("content-desc") or "").strip()
        action_flag = any(
            (node.attrib.get(flag) or "").strip().lower() == "true"
            for flag in ("clickable", "focusable", "scrollable", "long-clickable", "checkable")
        )
        if not (text or rid or desc or action_flag):
            return False

    # Require at least one meaningful node to keep XML mode.
    for node in nodes:
        text = (node.attrib.get("text") or "").strip()
        rid = (node.attrib.get("resource-id") or "").strip()
        desc = (node.attrib.get("content-desc") or "").strip()
        action_flag = any(
            (node.attrib.get(flag) or "").strip().lower() == "true"
            for flag in ("clickable", "focusable", "scrollable", "long-clickable", "checkable")
        )
        if text or rid or desc or action_flag:
            return True
    return False


def _extract_answer(raw: str) -> str:
    m = re.search(r"<answer>\s*(.*?)\s*</answer>", raw, flags=re.IGNORECASE | re.DOTALL)
    if m:
        return m.group(1).strip()
    # Fallback for models that ignore the wrapper format.
    line_match = re.search(r'(finish\([^)]*\)|do\([^)]*\))', raw, flags=re.DOTALL)
    if line_match:
        return line_match.group(1).strip()
    return raw.strip()


def _parse_quoted(arg_text: str, key: str) -> str | None:
    m = re.search(rf'{re.escape(key)}\s*=\s*"((?:[^"\\\\]|\\\\.)*)"', arg_text)
    if not m:
        return None
    v = m.group(1)
    v = v.replace(r"\\", "\\").replace(r"\"", "\"")
    return v


def _parse_pair(arg_text: str, key: str) -> tuple[int, int] | None:
    m = re.search(
        rf"{re.escape(key)}\s*=\s*(?:\"|')?\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\](?:\"|')?",
        arg_text,
    )
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def _parse_int(arg_text: str, key: str) -> int | None:
    m = re.search(rf"{re.escape(key)}\s*=\s*(-?\d+)", arg_text)
    if not m:
        return None
    return int(m.group(1))


def parse_action(answer: str) -> dict[str, Any]:
    text = answer.strip()
    if text.startswith("finish("):
        msg = _parse_quoted(text, "message") or ""
        return {"kind": "finish", "message": msg}

    m = re.match(r'do\(\s*action\s*=\s*"([^"]+)"(.*)\)\s*$', text, flags=re.DOTALL)
    if not m:
        return {"kind": "invalid", "raw": text, "reason": "cannot parse action format"}

    action = m.group(1).strip()
    args = m.group(2)

    if action == "Tap":
        p = _parse_pair(args, "element")
        if not p:
            return {"kind": "invalid", "raw": text, "reason": "missing element=[x,y]"}
        return {"kind": "tap", "x": p[0], "y": p[1]}

    if action == "Swipe":
        s = _parse_pair(args, "start")
        e = _parse_pair(args, "end")
        d = _parse_int(args, "durationMs")
        if not s or not e:
            return {"kind": "invalid", "raw": text, "reason": "missing start/end"}
        return {"kind": "swipe", "sx": s[0], "sy": s[1], "ex": e[0], "ey": e[1], "durationMs": d or 300}

    if action in ("Type", "Type_Name"):
        text_v = _parse_quoted(args, "text")
        if text_v is None:
            return {"kind": "invalid", "raw": text, "reason": "missing text"}
        enter_action = _parse_quoted(args, "enterAction")
        return {"kind": "type", "text": text_v, "enterAction": enter_action}

    if action == "Wait":
        duration = _parse_quoted(args, "duration") or "2 seconds"
        m_d = re.search(r"(\d+)", duration)
        sec = int(m_d.group(1)) if m_d else 2
        sec = max(1, min(sec, 15))
        return {"kind": "wait", "seconds": sec}

    return {"kind": "invalid", "raw": text, "reason": f"unsupported action={action}"}


def _action_summary(action: dict[str, Any]) -> str:
    kind = action.get("kind")
    if kind == "tap":
        return f"tap({action.get('x')},{action.get('y')})"
    if kind == "swipe":
        return (
            f"swipe({action.get('sx')},{action.get('sy')}->"
            f"{action.get('ex')},{action.get('ey')},d={action.get('durationMs')})"
        )
    if kind == "type":
        txt = str(action.get("text", ""))
        short = txt if len(txt) <= 20 else txt[:20] + "..."
        ea = action.get("enterAction")
        return f"type(text={short!r},enter={ea})"
    if kind == "wait":
        return f"wait({action.get('seconds')}s)"
    if kind == "finish":
        return f"finish({action.get('message', '')!r})"
    return str(action)


def build_user_prompt(
    task: str,
    step: int,
    max_steps: int,
    click_range: dict[str, Any],
    history: list[str],
    state: UiState,
    xml_max_chars: int,
    same_state_streak: int,
    last_tap_point: tuple[int, int] | None,
    focus_ctx: FocusContext | None,
) -> str:
    history_text = "\n".join(history[-12:]) if history else "(none)"
    loop_signal = (
        f"same_ui_streak={same_state_streak}, "
        f"last_tap={last_tap_point if last_tap_point is not None else 'none'}"
    )
    if state.source == "xml":
        state_text = state.text[:xml_max_chars]
        state_block = f"UI source: XML\n{state_text}"
    else:
        if focus_ctx is not None:
            state_block = (
                "UI source: SCREENSHOT.\n"
                "Two images are attached: full screenshot + local crop from last ineffective tap.\n"
                f"Local crop center=({focus_ctx.center_x},{focus_ctx.center_y}), "
                f"radius={focus_ctx.radius_px}px, box=[{focus_ctx.box_left},{focus_ctx.box_top}]"
                f"-[{focus_ctx.box_right},{focus_ctx.box_bottom}], with local grid overlay."
            )
        else:
            state_block = "UI source: SCREENSHOT (full image attached)."

    return f"""Task: {task}
Step: {step}/{max_steps}
Click range: x={click_range.get("xMin",0)}..{click_range.get("xMax",999)}, y={click_range.get("yMin",0)}..{click_range.get("yMax",999)}
Loop signals: {loop_signal}
Recent action history:
{history_text}

Current UI:
{state_block}

Now output exactly one next action using required format.
If repeating Tap, make coordinate drift >= 50px from previous Tap.
"""


def build_strict_user_prompt(
    task: str,
    step: int,
    max_steps: int,
    click_range: dict[str, Any],
    history: list[str],
    state: UiState,
    xml_max_chars: int,
    same_state_streak: int,
    last_tap_point: tuple[int, int] | None,
    focus_ctx: FocusContext | None,
) -> str:
    history_text = "\n".join(history[-10:]) if history else "(none)"
    loop_signal = (
        f"same_ui_streak={same_state_streak}, "
        f"last_tap={last_tap_point if last_tap_point is not None else 'none'}"
    )
    if state.source == "xml":
        state_text = state.text[:xml_max_chars]
        state_block = f"UI source: XML\n{state_text}"
    else:
        if focus_ctx is not None:
            state_block = (
                "UI source: SCREENSHOT with two images attached (full + local focus crop with grid). "
                f"focus_center=({focus_ctx.center_x},{focus_ctx.center_y}), radius={focus_ctx.radius_px}px."
            )
        else:
            state_block = "UI source: SCREENSHOT (image attached)."
    return f"""Task: {task}
Step: {step}/{max_steps}
Click range: x={click_range.get("xMin",0)}..{click_range.get("xMax",999)}, y={click_range.get("yMin",0)}..{click_range.get("yMax",999)}
Loop signals: {loop_signal}
Recent action history:
{history_text}

Current UI:
{state_block}

Return ONE action line now.
If repeating Tap, coordinate drift must be >= 50px.
"""


def _decode_image_rgba(image_b64: str):
    from PIL import Image  # type: ignore

    raw = base64.b64decode(image_b64)
    with Image.open(io.BytesIO(raw)) as img:
        return img.convert("RGBA")


def _encode_image_png_base64(image) -> str:
    out = io.BytesIO()
    image.save(out, format="PNG")
    return base64.b64encode(out.getvalue()).decode("ascii")


def _is_near_solid(image, tolerance: int = 3) -> bool:
    gray = image.convert("L")
    lo, hi = gray.getextrema()
    return (hi - lo) <= tolerance


def _draw_local_grid(image, center_x: int, center_y: int, left: int, top: int, grid_px: int):
    from PIL import ImageDraw  # type: ignore

    draw = ImageDraw.Draw(image)
    w, h = image.size
    spacing = max(5, grid_px)
    for x in range(0, w, spacing):
        draw.line((x, 0, x, h), fill=(255, 40, 40, 120), width=1)
    for y in range(0, h, spacing):
        draw.line((0, y, w, y), fill=(255, 40, 40, 120), width=1)

    # Crosshair for the exact click point inside local crop.
    cx = max(0, min(w - 1, center_x - left))
    cy = max(0, min(h - 1, center_y - top))
    draw.line((max(0, cx - 10), cy, min(w - 1, cx + 10), cy), fill=(40, 255, 40, 220), width=2)
    draw.line((cx, max(0, cy - 10), cx, min(h - 1, cy + 10)), fill=(40, 255, 40, 220), width=2)
    return image


def build_focus_context(
    full_image_b64: str,
    center_x: int,
    center_y: int,
    step: int,
    focus_radius_px: int,
    focus_expand_step_px: int,
    focus_max_radius_px: int,
    local_grid_px: int,
    capture_dir: Path,
    save_debug_screenshot: bool,
) -> FocusContext | None:
    try:
        image = _decode_image_rgba(full_image_b64)
    except Exception as exc:  # noqa: BLE001
        print(f"[focus] decode failed: {exc}")
        return None

    w, h = image.size
    radius = max(10, focus_radius_px)
    max_radius = max(radius, focus_max_radius_px)
    expand_step = max(10, focus_expand_step_px)
    selected = None

    while radius <= max_radius:
        left = max(0, center_x - radius)
        top = max(0, center_y - radius)
        right = min(w, center_x + radius)
        bottom = min(h, center_y + radius)
        if right <= left or bottom <= top:
            break
        crop = image.crop((left, top, right, bottom))
        if _is_near_solid(crop):
            print(f"[focus] step={step} local crop near-solid at radius={radius}px, expand +{expand_step}px")
            selected = (crop, left, top, right, bottom, radius, True)
            radius += expand_step
            continue
        selected = (crop, left, top, right, bottom, radius, False)
        break

    if selected is None:
        return None

    crop, left, top, right, bottom, radius_used, was_solid = selected
    crop = _draw_local_grid(
        image=crop,
        center_x=center_x,
        center_y=center_y,
        left=left,
        top=top,
        grid_px=local_grid_px,
    )
    crop_b64 = _encode_image_png_base64(crop)
    reason = "expanded_from_solid" if was_solid else "direct"
    print(
        f"[focus] step={step} build local crop center=({center_x},{center_y}) "
        f"radius={radius_used}px box=[{left},{top}]-[{right},{bottom}] reason={reason}"
    )
    if save_debug_screenshot:
        stamp = time.time_ns()
        focus_path = capture_dir / f"step_{step:02d}_focus_{stamp}.png"
        _save_debug_image(focus_path, crop_b64)

    return FocusContext(
        image_b64=crop_b64,
        center_x=center_x,
        center_y=center_y,
        radius_px=radius_used,
        box_left=left,
        box_top=top,
        box_right=right,
        box_bottom=bottom,
        reason=reason,
    )


def _save_debug_image(path: Path, image_b64: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(base64.b64decode(image_b64))
    print(f"[capture] saved {path}")


def _save_debug_image_bytes(path: Path, image_bytes: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(image_bytes)
    print(f"[capture] saved {path}")


def fetch_ui_state(
    client: ActlClient,
    save_debug_screenshot: bool,
    step: int,
    screenshot_grid_px: int,
    capture_dir: Path,
    capture_reason: str,
) -> UiState:
    shot = client.get_ui_screenshot()
    image_b64 = shot.image_b64
    if save_debug_screenshot:
        stamp = time.time_ns()
        full_path = capture_dir / f"step_{step:02d}_{capture_reason}_{stamp}_full.png"
        _save_debug_image_bytes(full_path, shot.png_bytes)
    ah = _compute_screen_ahash(shot.png_bytes)
    sig = hashlib.sha256(shot.png_bytes).hexdigest()
    return UiState(
        source="screenshot",
        text="",
        image_b64=image_b64,
        state_sig=f"shot:{sig}",
        screen_ahash=ah,
    )


def _compute_screen_ahash(image_bytes: bytes, size: int = 8) -> int | None:
    try:
        from PIL import Image  # type: ignore
    except Exception:
        return None
    try:
        with Image.open(io.BytesIO(image_bytes)) as img:
            g = img.convert("L").resize((size, size))
            # L mode is 8-bit grayscale, so tobytes() is a flat pixel buffer.
            pixels = list(g.tobytes())
            if not pixels:
                return None
            avg = sum(pixels) / len(pixels)
            bits = 0
            for p in pixels:
                bits = (bits << 1) | (1 if p >= avg else 0)
            return bits
    except Exception:
        return None


def _hamming_distance(a: int, b: int) -> int:
    return (a ^ b).bit_count()


def _states_similar(prev: UiState | None, curr: UiState | None, ahash_threshold: int) -> tuple[bool, str]:
    if prev is None or curr is None:
        return False, "missing_state"
    if prev.source != curr.source:
        return False, "source_changed"
    if curr.source == "xml":
        same = prev.state_sig == curr.state_sig
        return same, "xml_sig_equal" if same else "xml_sig_changed"

    if prev.screen_ahash is not None and curr.screen_ahash is not None:
        dist = _hamming_distance(prev.screen_ahash, curr.screen_ahash)
        return dist <= ahash_threshold, f"ahash_dist={dist}"

    same = prev.state_sig == curr.state_sig
    return same, "shot_sig_equal" if same else "shot_sig_changed"


def _clip(v: int, low: int, high: int) -> int:
    return max(low, min(high, v))


def _tap_with_retry(
    client: ActlClient,
    base_state: UiState,
    x: int,
    y: int,
    click_range: dict[str, Any],
    save_debug_screenshot: bool,
    step: int,
    wait_after_tap_sec: float,
    max_retry: int,
    screenshot_grid_px: int,
    capture_dir: Path,
    ahash_threshold: int,
) -> tuple[bool, str, bool, tuple[int, int]]:
    offsets = [
        (0, 0),
        (50, 0),
        (-50, 0),
        (0, 50),
        (0, -50),
        (80, 80),
        (-80, 80),
    ]
    xmin = int(click_range.get("xMin", 0))
    ymin = int(click_range.get("yMin", 0))
    xmax = int(click_range.get("xMax", 1079))
    ymax = int(click_range.get("yMax", 2339))
    last_body = ""
    any_success = False
    final_point = (x, y)
    attempts = min(max_retry + 1, len(offsets))
    for i in range(attempts):
        dx, dy = offsets[i]
        tx = _clip(x + dx, xmin, xmax)
        ty = _clip(y + dy, ymin, ymax)
        final_point = (tx, ty)
        ok, body = client.click(tx, ty)
        last_body = body
        print(f"[tap] attempt={i + 1}/{attempts} point=[{tx},{ty}] ok={ok}")
        if not ok:
            continue
        any_success = True
        time.sleep(wait_after_tap_sec)
        after = fetch_ui_state(
            client=client,
            save_debug_screenshot=save_debug_screenshot,
            step=step,
            screenshot_grid_px=screenshot_grid_px,
            capture_dir=capture_dir,
            capture_reason=f"tap_retry_{i + 1}",
        )
        same, reason = _states_similar(base_state, after, ahash_threshold)
        if not same:
            if i > 0:
                print("[tap] retry hit: UI changed")
            else:
                print(f"[tap] UI changed after attempt {i + 1}: {reason}")
            return True, body, True, final_point
        if i < attempts - 1:
            print(f"[tap] no visible UI change ({reason}), retry with offset point")
    return any_success, last_body, False, final_point


def run_agent(config: dict[str, Any], task: str, max_steps: int) -> int:
    actl_cfg = config["actl"]
    vlm_cfg = config["vlm"]
    agent_cfg = config.get("agent", {})

    client = ActlClient(actl_cfg["base_url"], int(actl_cfg.get("timeout_sec", 20)))
    vlm = OpenAICompatClient(
        vlm_cfg["base_url"],
        vlm_cfg["api_key"],
        vlm_cfg["model"],
        int(vlm_cfg.get("timeout_sec", 60)),
        float(vlm_cfg["temperature"]) if "temperature" in vlm_cfg else None,
        int(vlm_cfg["max_tokens"]) if "max_tokens" in vlm_cfg else None,
        vlm_cfg.get("thinking"),
        vlm_cfg.get("extra_body"),
    )

    info = client.get_system_info()
    click_range = info.get("data", {}).get("clickRange", {"xMin": 0, "yMin": 0, "xMax": 999, "yMax": 999})
    xml_max_chars = int(agent_cfg.get("xml_max_chars", 28000))
    save_debug_screenshot = bool(agent_cfg.get("save_debug_screenshot", True))
    wait_after_tap_sec = float(agent_cfg.get("wait_after_tap_sec", 0.35))
    tap_retry_on_no_change = int(agent_cfg.get("tap_retry_on_no_change", 2))
    invalid_action_retry = int(agent_cfg.get("invalid_action_retry", 1))
    screenshot_grid_px = int(agent_cfg.get("screenshot_grid_px", 50))
    min_tap_drift_px = int(agent_cfg.get("min_tap_drift_px", 50))
    capture_dir = Path(agent_cfg.get("capture_dir", "tools/agent_captures"))
    post_state_check = bool(agent_cfg.get("post_state_check", True))
    focus_radius_px = int(agent_cfg.get("focus_radius_px", 200))
    focus_expand_step_px = int(agent_cfg.get("focus_expand_step_px", 50))
    focus_max_radius_px = int(agent_cfg.get("focus_max_radius_px", 250))
    focus_grid_px = int(agent_cfg.get("focus_grid_px", 10))
    screenshot_ahash_threshold = int(agent_cfg.get("screenshot_ahash_threshold", 6))

    print(f"[agent] target={actl_cfg['base_url']} task={task}")
    print(f"[agent] clickRange={click_range}")
    print("[agent] mode=vlm-only (screenshot driven)")
    print(
        f"[agent] captureDir={capture_dir} saveCapture={save_debug_screenshot} "
        f"focusRadius={focus_radius_px}px focusGrid={focus_grid_px}px"
    )

    history: list[str] = []
    prev_state: UiState | None = None
    same_state_streak = 0
    last_tap_point: tuple[int, int] | None = None
    pending_focus_ctx: FocusContext | None = None
    for step in range(1, max_steps + 1):
        focus_for_this_step = pending_focus_ctx
        pending_focus_ctx = None

        state = fetch_ui_state(
            client=client,
            save_debug_screenshot=save_debug_screenshot,
            step=step,
            screenshot_grid_px=screenshot_grid_px,
            capture_dir=capture_dir,
            capture_reason="main",
        )
        same_as_prev, state_reason = _states_similar(prev_state, state, screenshot_ahash_threshold)
        if prev_state is not None:
            print(f"[state] same_as_prev={same_as_prev} reason={state_reason}")
        if prev_state is not None and same_as_prev:
            same_state_streak += 1
        else:
            same_state_streak = 0
        prev_state = state
        if same_state_streak >= 2:
            print(f"[loop] same UI streak={same_state_streak} (possible loop)")

        prompt = build_user_prompt(
            task=task,
            step=step,
            max_steps=max_steps,
            click_range=click_range,
            history=history,
            state=state,
            xml_max_chars=xml_max_chars,
            same_state_streak=same_state_streak,
            last_tap_point=last_tap_point,
            focus_ctx=focus_for_this_step,
        )

        images = [state.image_b64]
        if focus_for_this_step is not None:
            images.append(focus_for_this_step.image_b64)
            print(
                f"[focus] step={step} provide full+local images "
                f"(local_center=({focus_for_this_step.center_x},{focus_for_this_step.center_y}), "
                f"radius={focus_for_this_step.radius_px})"
            )
        raw = vlm.chat_with_images(prompt, images)
        model_used = "vlm"
        finish_reason = vlm.last_finish_reason

        answer = _extract_answer(raw)
        action = parse_action(answer)
        print(f"\n[step {step}] source={state.source} model={model_used}")
        print(f"[model-finish-reason] {finish_reason}")
        print(f"[model-raw]\n{raw}")
        print(f"[model-action] {answer}")

        if action["kind"] == "finish":
            print(f"[finish] {action.get('message', '')}")
            return 0

        recovered_from_invalid = False
        if action["kind"] == "invalid":
            err = action.get("reason", "invalid action")
            print(f"[error] invalid action: {err}")

            recovered = False
            for attempt in range(1, max(0, invalid_action_retry) + 1):
                strict_user = build_strict_user_prompt(
                    task=task,
                    step=step,
                    max_steps=max_steps,
                    click_range=click_range,
                    history=history,
                    state=state,
                    xml_max_chars=xml_max_chars,
                    same_state_streak=same_state_streak,
                    last_tap_point=last_tap_point,
                    focus_ctx=focus_for_this_step,
                )
                raw2 = vlm._chat(
                    [
                        {"role": "system", "content": STRICT_ACTION_PROMPT},
                        {
                            "role": "user",
                            "content": (
                                [{"type": "text", "text": strict_user}]
                                + [{"type": "image_url", "image_url": {"url": f"data:image/png;base64,{state.image_b64}"}}]
                                + (
                                    []
                                    if focus_for_this_step is None
                                    else [{
                                        "type": "image_url",
                                        "image_url": {"url": f"data:image/png;base64,{focus_for_this_step.image_b64}"},
                                    }]
                                )
                            ),
                        },
                    ]
                )
                finish_reason2 = vlm.last_finish_reason
                model_used2 = "vlm"

                answer2 = _extract_answer(raw2)
                action2 = parse_action(answer2)
                print(f"[recover {attempt}] model={model_used2} finish_reason={finish_reason2}")
                print(f"[recover-raw]\n{raw2}")
                print(f"[recover-action] {answer2}")

                if action2["kind"] != "invalid":
                    action = action2
                    recovered = True
                    recovered_from_invalid = True
                    break

            if not recovered:
                history.append(
                    f"step={step} model_invalid reason={err} finish_reason={finish_reason} "
                    f"same_ui_streak={same_state_streak}"
                )
                continue

        ok = False
        result_body = ""
        ui_changed = False
        final_tap_point: tuple[int, int] | None = None
        post_state: UiState | None = None
        if action["kind"] == "tap":
            target_x = int(action["x"])
            target_y = int(action["y"])
            if same_state_streak >= 1 and last_tap_point is not None:
                lx, ly = last_tap_point
                manhattan = abs(target_x - lx) + abs(target_y - ly)
                if manhattan < min_tap_drift_px:
                    print(f"[loop-guard] tap drift {manhattan}px < {min_tap_drift_px}px, force drift")
                    xmin = int(click_range.get("xMin", 0))
                    ymin = int(click_range.get("yMin", 0))
                    xmax = int(click_range.get("xMax", 1079))
                    ymax = int(click_range.get("yMax", 2339))
                    if target_x + min_tap_drift_px <= xmax:
                        target_x += min_tap_drift_px
                    elif target_x - min_tap_drift_px >= xmin:
                        target_x -= min_tap_drift_px
                    elif target_y + min_tap_drift_px <= ymax:
                        target_y += min_tap_drift_px
                    else:
                        target_y = max(ymin, target_y - min_tap_drift_px)

            ok, result_body, ui_changed, final_tap = _tap_with_retry(
                client=client,
                base_state=state,
                x=target_x,
                y=target_y,
                click_range=click_range,
                save_debug_screenshot=save_debug_screenshot,
                step=step,
                wait_after_tap_sec=wait_after_tap_sec,
                max_retry=tap_retry_on_no_change,
                screenshot_grid_px=screenshot_grid_px,
                capture_dir=capture_dir,
                ahash_threshold=screenshot_ahash_threshold,
            )
            final_tap_point = final_tap
            last_tap_point = final_tap
        elif action["kind"] == "swipe":
            ok, result_body = client.swipe(
                int(action["sx"]),
                int(action["sy"]),
                int(action["ex"]),
                int(action["ey"]),
                int(action["durationMs"]),
            )
            last_tap_point = None
        elif action["kind"] == "type":
            ok, result_body = client.input_text(str(action["text"]), action.get("enterAction"))
            last_tap_point = None
        elif action["kind"] == "wait":
            sec = int(action["seconds"])
            print(f"[wait] {sec}s")
            time.sleep(sec)
            ok = True
            result_body = '{"code":0,"message":"wait done"}'
            last_tap_point = None

        if post_state_check and ok:
            post_state = fetch_ui_state(
                client=client,
                save_debug_screenshot=save_debug_screenshot,
                step=step,
                screenshot_grid_px=screenshot_grid_px,
                capture_dir=capture_dir,
                capture_reason="post",
            )
            post_same, post_reason = _states_similar(state, post_state, screenshot_ahash_threshold)
            post_changed = not post_same
            print(f"[state-post] changed={post_changed} reason={post_reason}")
            ui_changed = ui_changed or post_changed

        print(f"[exec] ok={ok} body={result_body[:280]}")
        if (
            action["kind"] == "tap"
            and ok
            and not ui_changed
            and final_tap_point is not None
        ):
            print("[focus] tap ineffective, preparing local focus crop for next step")
            if post_state is None or post_state.source != "screenshot":
                # Ensure we have a full screenshot as focus generation base.
                shot = client.get_ui_screenshot()
                shot_b64 = shot.image_b64
                if save_debug_screenshot:
                    stamp = time.time_ns()
                    base_path = capture_dir / f"step_{step:02d}_focus_base_{stamp}.png"
                    _save_debug_image_bytes(base_path, shot.png_bytes)
                focus_base_b64 = shot_b64
            else:
                focus_base_b64 = post_state.image_b64

            pending_focus_ctx = build_focus_context(
                full_image_b64=focus_base_b64,
                center_x=final_tap_point[0],
                center_y=final_tap_point[1],
                step=step,
                focus_radius_px=focus_radius_px,
                focus_expand_step_px=focus_expand_step_px,
                focus_max_radius_px=focus_max_radius_px,
                local_grid_px=focus_grid_px,
                capture_dir=capture_dir,
                save_debug_screenshot=save_debug_screenshot,
            )
        else:
            if action["kind"] == "tap" and ok:
                print(f"[focus] skip local focus (ui_changed={ui_changed})")
            pending_focus_ctx = None

        history.append(
            f"step={step} action={_action_summary(action)} ok={ok} ui_changed={ui_changed} "
            f"same_ui_streak={same_state_streak} recovered={recovered_from_invalid} "
            f"finish_reason={finish_reason} focus_next={'yes' if pending_focus_ctx is not None else 'no'}"
        )

    print("[finish] max steps reached without finish action")
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(description="ACTL XML-first LLM/VLM automation agent")
    parser.add_argument("--task", required=True, help="Task instruction for the agent")
    parser.add_argument(
        "--config",
        default="tools/actl_agent_config.json",
        help="Path to config JSON",
    )
    parser.add_argument("--max-steps", type=int, default=12, help="Max action steps")
    args = parser.parse_args()

    config_path = Path(args.config)
    if not config_path.is_file():
        print(f"Config file not found: {config_path}", file=sys.stderr)
        return 1

    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        return run_agent(config, args.task, args.max_steps)
    except Exception as exc:  # noqa: BLE001
        print(f"[fatal] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
