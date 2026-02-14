#!/usr/bin/env python3

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import math
import re
import sqlite3
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

try:
    from PIL import Image
except Exception:  # pragma: no cover - runtime dependency guard
    Image = None  # type: ignore[assignment]


class ControllerError(RuntimeError):
    pass


@dataclass
class ClickRange:
    x_min: int
    y_min: int
    x_max: int
    y_max: int


class DroidNodeClient:
    def __init__(self, base_url: str, timeout_sec: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_sec = timeout_sec

    def _request_json(self, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, str]:
        url = f"{self.base_url}{path}"
        headers = {"Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url=url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                return resp.status, resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read().decode("utf-8", errors="replace")
        except urllib.error.URLError as exc:
            raise ControllerError(f"ACTL request failed: {exc}") from exc

    def _request_bytes(self, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, bytes, str]:
        url = f"{self.base_url}{path}"
        headers = {"Accept": "*/*"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url=url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                return int(resp.status), resp.read(), resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as exc:
            content_type = exc.headers.get("Content-Type", "") if exc.headers else ""
            return int(exc.code), exc.read(), content_type
        except urllib.error.URLError as exc:
            raise ControllerError(f"ACTL request failed: {exc}") from exc

    def get_click_range(self) -> ClickRange:
        status, body = self._request_json("GET", "/v1/system/info")
        if status != 200:
            raise ControllerError(f"/v1/system/info failed: HTTP {status}, body={body}")
        obj = json.loads(body)
        data = obj.get("data", {})
        cr = data.get("clickRange", {})
        return ClickRange(
            x_min=int(cr.get("xMin", 0)),
            y_min=int(cr.get("yMin", 0)),
            x_max=int(cr.get("xMax", 1079)),
            y_max=int(cr.get("yMax", 2339)),
        )

    def screenshot_png(self) -> bytes:
        status, body, content_type = self._request_bytes("POST", "/v1/ui/screenshot", {})
        if status != 200:
            preview = body.decode("utf-8", errors="replace")
            raise ControllerError(f"/v1/ui/screenshot failed: HTTP {status}, body={preview}")
        if "image/png" not in content_type.lower():
            preview = body.decode("utf-8", errors="replace")
            raise ControllerError(f"/v1/ui/screenshot unexpected content type={content_type}, body={preview}")
        if not body:
            raise ControllerError("/v1/ui/screenshot returned empty image")
        return body

    def click(self, x: int, y: int) -> tuple[bool, str]:
        status, body = self._request_json("POST", "/v1/control/click", {"x": x, "y": y})
        return status == 200, body

    def swipe(self, start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int = 300) -> tuple[bool, str]:
        payload = {
            "startX": start_x,
            "startY": start_y,
            "endX": end_x,
            "endY": end_y,
            "durationMs": duration_ms,
        }
        status, body = self._request_json("POST", "/v1/control/swipe", payload)
        return status == 200, body

    def input_text(self, text: str, enter_action: str = "none", press_enter: bool | None = None) -> tuple[bool, str]:
        if press_enter is None:
            press_enter = enter_action != "none"
        payload = {"text": text, "pressEnter": bool(press_enter), "enterAction": enter_action}
        status, body = self._request_json("POST", "/v1/control/input", payload)
        return status == 200, body


class VlmClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_sec: int,
        temperature: float | None,
        max_tokens: int | None,
        thinking: dict[str, Any] | bool | None = None,
    ) -> None:
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
        self.last_finish_reason = ""

    def ask(self, prompt: str, images_png: list[bytes]) -> str:
        content: list[dict[str, Any]] = []
        for image in images_png:
            image_b64 = base64.b64encode(image).decode("ascii")
            content.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:image/png;base64,{image_b64}"},
                }
            )
        content.append({"type": "text", "text": prompt})

        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [{"role": "user", "content": content}],
        }
        if self.temperature is not None:
            payload["temperature"] = self.temperature
        if self.max_tokens is not None:
            payload["max_tokens"] = self.max_tokens
        if self.thinking is not None:
            payload["thinking"] = self.thinking

        req = urllib.request.Request(
            url=self.base_url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_sec) as resp:
                body = resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise ControllerError(f"VLM HTTP {exc.code}: {body}") from exc
        except urllib.error.URLError as exc:
            raise ControllerError(f"VLM request failed: {exc}") from exc

        obj = json.loads(body)
        choices = obj.get("choices", [])
        first = choices[0] if isinstance(choices, list) and choices else {}
        self.last_finish_reason = str(first.get("finish_reason", ""))
        message = first.get("message", {}) if isinstance(first, dict) else {}
        content_obj = message.get("content", "")
        if isinstance(content_obj, list):
            texts = []
            for item in content_obj:
                if isinstance(item, dict):
                    item_type = str(item.get("type", ""))
                    if item_type in ("text", "output_text"):
                        texts.append(str(item.get("text", item.get("content", ""))))
            return "\n".join(texts).strip()
        return str(content_obj).strip()


def _build_vlm_client(cfg: dict[str, Any], fallback: dict[str, Any] | None = None) -> VlmClient:
    merged: dict[str, Any] = {}
    if fallback:
        merged.update(fallback)
    merged.update(cfg)
    return VlmClient(
        base_url=merged["base_url"],
        api_key=merged["api_key"],
        model=merged["model"],
        timeout_sec=int(merged.get("timeout_sec", 60)),
        temperature=float(merged["temperature"]) if "temperature" in merged else None,
        max_tokens=int(merged["max_tokens"]) if "max_tokens" in merged else None,
        thinking=merged.get("thinking"),
    )


class MemoryStore:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.db_path))
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS memory_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                task TEXT NOT NULL,
                step INTEGER NOT NULL,
                screen_hash TEXT NOT NULL,
                action TEXT NOT NULL,
                target TEXT,
                region INTEGER,
                x INTEGER,
                y INTEGER,
                ok INTEGER NOT NULL,
                detail TEXT
            )
            """
        )
        self.conn.commit()

    def add_event(
        self,
        task: str,
        step: int,
        screen_hash: str,
        action: str,
        target: str,
        region: int | None,
        x: int | None,
        y: int | None,
        ok: bool,
        detail: str,
    ) -> None:
        self.conn.execute(
            """
            INSERT INTO memory_events
              (created_at, task, step, screen_hash, action, target, region, x, y, ok, detail)
            VALUES
              (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                datetime.now().isoformat(timespec="seconds"),
                task,
                step,
                screen_hash,
                action,
                target,
                region,
                x,
                y,
                1 if ok else 0,
                detail[:400],
            ),
        )
        self.conn.commit()

    def recent_for_task(self, task: str, limit: int) -> list[dict[str, Any]]:
        cur = self.conn.execute(
            """
            SELECT step, action, target, region, x, y, ok, detail
            FROM memory_events
            WHERE task = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (task, limit),
        )
        rows = []
        for r in cur.fetchall():
            rows.append(
                {
                    "step": int(r[0]),
                    "action": str(r[1]),
                    "target": str(r[2] or ""),
                    "region": r[3],
                    "x": r[4],
                    "y": r[5],
                    "ok": bool(r[6]),
                    "detail": str(r[7] or ""),
                }
            )
        return rows


def _extract_json(raw: str) -> dict[str, Any] | None:
    text = raw.strip()
    # Strip markdown code fence if present.
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z0-9_-]*\n?", "", text)
        text = re.sub(r"\n?```$", "", text)
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return obj
    except Exception:
        pass

    m = re.search(r"\{.*\}", raw, flags=re.DOTALL)
    if m:
        frag = m.group(0)
        try:
            obj = json.loads(frag)
            if isinstance(obj, dict):
                return obj
        except Exception:
            return None
    return None


def _extract_planner_fallback(raw: str) -> dict[str, Any]:
    # Tolerate truncated/non-JSON planner output by extracting key fields.
    out: dict[str, Any] = {}
    action_m = re.search(r'"action"\s*:\s*"([^"]+)"', raw, flags=re.IGNORECASE)
    if action_m:
        out["action"] = action_m.group(1).strip().lower()
    target_m = re.search(r'"target"\s*:\s*"([^"]*)"', raw, flags=re.IGNORECASE)
    if target_m:
        out["target"] = target_m.group(1).strip()
    region_m = re.search(r'"region"\s*:\s*(\d+)', raw, flags=re.IGNORECASE)
    if region_m:
        out["region"] = int(region_m.group(1))
    regions_m = re.search(r'"regions"\s*:\s*\[([^\]]+)\]', raw, flags=re.IGNORECASE)
    if regions_m:
        nums = re.findall(r"\d+", regions_m.group(1))
        if nums:
            out["regions"] = [int(n) for n in nums]
    text_m = re.search(r'"text"\s*:\s*"([^"]*)"', raw, flags=re.IGNORECASE)
    if text_m:
        out["text"] = text_m.group(1)
    enter_m = re.search(r'"enterAction"\s*:\s*"([^"]+)"', raw, flags=re.IGNORECASE)
    if enter_m:
        out["enterAction"] = enter_m.group(1).strip().lower()
    wait_m = re.search(r'"waitSec"\s*:\s*(\d+)', raw, flags=re.IGNORECASE)
    if wait_m:
        out["waitSec"] = int(wait_m.group(1))
    finish_m = re.search(r'"finishMessage"\s*:\s*"([^"]*)"', raw, flags=re.IGNORECASE)
    if finish_m:
        out["finishMessage"] = finish_m.group(1)
    swipe_target_m = re.search(r'"swipeTarget"\s*:\s*"([^"]*)"', raw, flags=re.IGNORECASE)
    if swipe_target_m:
        out["swipeTarget"] = swipe_target_m.group(1).strip()
    direction_m = re.search(r'"direction"\s*:\s*"(up|down|left|right)"', raw, flags=re.IGNORECASE)
    if direction_m:
        out["direction"] = direction_m.group(1).lower()
    duration_m = re.search(r'"durationMs"\s*:\s*(\d+)', raw, flags=re.IGNORECASE)
    if duration_m:
        out["durationMs"] = int(duration_m.group(1))
    start_region_m = re.search(r'"startRegion"\s*:\s*(\d+)', raw, flags=re.IGNORECASE)
    if start_region_m:
        out["startRegion"] = int(start_region_m.group(1))
    end_region_m = re.search(r'"endRegion"\s*:\s*(\d+)', raw, flags=re.IGNORECASE)
    if end_region_m:
        out["endRegion"] = int(end_region_m.group(1))
    for key in ("startX", "startY", "endX", "endY"):
        m = re.search(rf'"{key}"\s*:\s*(-?\d+)', raw, flags=re.IGNORECASE)
        if m:
            out[key] = int(m.group(1))
    for key in ("startXRatio", "startYRatio", "endXRatio", "endYRatio"):
        m = re.search(rf'"{key}"\s*:\s*(-?\d+(?:\.\d+)?)', raw, flags=re.IGNORECASE)
        if m:
            out[key] = float(m.group(1))
    press_enter_m = re.search(r'"pressEnter"\s*:\s*(true|false)', raw, flags=re.IGNORECASE)
    if press_enter_m:
        out["pressEnter"] = press_enter_m.group(1).lower() == "true"
    return out


def _clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def _safe_int(value: Any, default: int) -> int:
    try:
        if isinstance(value, str):
            value = value.strip()
        return int(value)
    except Exception:
        return default


def _safe_float(value: Any, default: float) -> float:
    try:
        if isinstance(value, str):
            m = re.search(r"-?\d+(?:\.\d+)?", value)
            if m:
                return float(m.group(0))
        return float(value)
    except Exception:
        return default


def _choose_adaptive_grid(
    width: int,
    height: int,
    min_segments: int = 3,
    max_segments: int = 12,
    target_log_error: float = 0.2,
) -> tuple[int, int, int, float, float]:
    candidates: list[tuple[int, int, int, float, float]] = []
    for rows in range(min_segments, max_segments + 1):
        for cols in range(min_segments, max_segments + 1):
            cell_w = width / cols
            cell_h = height / rows
            if cell_w <= 1 or cell_h <= 1:
                continue
            cell_aspect = cell_w / cell_h
            log_error = abs(math.log(max(1e-6, cell_aspect)))
            cells = rows * cols
            candidates.append((rows, cols, cells, cell_aspect, log_error))

    if not candidates:
        return 3, 3, 9, width / max(1.0, float(height)), abs(math.log(max(1e-6, width / max(1.0, float(height)))))

    near_square = [c for c in candidates if c[4] <= target_log_error]
    if near_square:
        rows, cols, cells, cell_aspect, log_error = min(near_square, key=lambda c: (c[2], c[4]))
    else:
        rows, cols, cells, cell_aspect, log_error = min(candidates, key=lambda c: (c[4], c[2]))
    return rows, cols, cells, cell_aspect, log_error


def _region_box(region: int, rows: int, cols: int, width: int, height: int) -> tuple[int, int, int, int]:
    r = _clamp(region, 1, rows * cols) - 1
    row = r // cols
    col = r % cols
    cell_w = width // cols
    cell_h = height // rows
    left = col * cell_w
    top = row * cell_h
    right = width if col == (cols - 1) else (col + 1) * cell_w
    bottom = height if row == (rows - 1) else (row + 1) * cell_h
    return left, top, right, bottom


def _region_row_col(region: int, rows: int, cols: int) -> tuple[int, int]:
    r = _clamp(region, 1, rows * cols) - 1
    return r // cols, r % cols


def _parse_regions_value(value: Any) -> list[int]:
    if isinstance(value, list):
        out: list[int] = []
        for v in value:
            try:
                out.append(int(v))
            except Exception:
                pass
        return out
    if isinstance(value, str):
        nums = re.findall(r"\d+", value)
        return [int(n) for n in nums]
    return []


def _looks_like_horizontal_span_target(target: str) -> bool:
    t = target.lower()
    keywords = (
        "input",
        "field",
        "search",
        "bar",
        "message",
        "textbox",
        "text box",
        "输入",
        "搜索",
        "消息",
        "聊天",
    )
    return any(k in t for k in keywords)


def _rect_cover_from_regions(
    planner: dict[str, Any],
    rows: int,
    cols: int,
    width: int,
    height: int,
) -> tuple[list[int], list[int], tuple[int, int, int, int], tuple[int, int, int, int]]:
    cells = rows * cols
    regions_raw = _parse_regions_value(planner.get("regions"))
    single = _safe_int(planner.get("region", 0), 0)
    if single > 0:
        regions_raw.append(single)

    normalized = sorted({ _clamp(r, 1, cells) for r in regions_raw if r > 0 })
    if not normalized:
        center_region = (rows // 2) * cols + (cols // 2) + 1
        normalized = [_clamp(center_region, 1, cells)]

    # Heuristic: bars like input/search often span multiple adjacent columns.
    if len(normalized) == 1 and _looks_like_horizontal_span_target(str(planner.get("target", ""))):
        row, _ = _region_row_col(normalized[0], rows, cols)
        normalized = [row * cols + c + 1 for c in range(cols)]

    row_cols = [_region_row_col(r, rows, cols) for r in normalized]
    min_row = min(rc[0] for rc in row_cols)
    max_row = max(rc[0] for rc in row_cols)
    min_col = min(rc[1] for rc in row_cols)
    max_col = max(rc[1] for rc in row_cols)

    rect_regions: list[int] = []
    for rr in range(min_row, max_row + 1):
        for cc in range(min_col, max_col + 1):
            rect_regions.append(rr * cols + cc + 1)

    cell_w = width // cols
    cell_h = height // rows
    left = min_col * cell_w
    top = min_row * cell_h
    right = width if max_col == (cols - 1) else (max_col + 1) * cell_w
    bottom = height if max_row == (rows - 1) else (max_row + 1) * cell_h

    return normalized, rect_regions, (left, top, right, bottom), (min_row, max_row, min_col, max_col)


def _point_from_region_ratio(
    region: int,
    rows: int,
    cols: int,
    width: int,
    height: int,
    xr: float,
    yr: float,
) -> tuple[int, int]:
    left, top, right, bottom = _region_box(region, rows, cols, width, height)
    xr = max(0.0, min(1.0, xr))
    yr = max(0.0, min(1.0, yr))
    x = int(left + xr * max(1, right - left - 1))
    y = int(top + yr * max(1, bottom - top - 1))
    return x, y


def _to_png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _planner_prompt(
    task: str,
    step: int,
    max_steps: int,
    rows: int,
    cols: int,
    memory_lines: str,
    history_lines: str,
) -> str:
    cell_count = rows * cols
    return f"""
You are a mobile operation planner.
Task: {task}
Step: {step}/{max_steps}
Grid: rows={rows}, cols={cols}, totalRegions={cell_count}
Recent memory:
{memory_lines}
Recent history:
{history_lines}

Return JSON only:
{{
  "action": "tap|swipe|ime_input|wait|finish",
  "target": "required when action=tap",
  "region": 1,
  "regions": [1,2,3],
  "swipeTarget": "optional description when action=swipe",
  "startRegion": 8,
  "endRegion": 2,
  "startXRatio": 0.5,
  "startYRatio": 0.5,
  "endXRatio": 0.5,
  "endYRatio": 0.5,
  "direction": "optional: up|down|left|right",
  "durationMs": 260,
  "text": "required when action=ime_input",
  "pressEnter": false,
  "enterAction": "none|auto|search|send|done|go|next|enter",
  "waitSec": 2,
  "finishMessage": "optional"
}}
Rules:
- One action only.
- Prefer shortest path.
- Regions are numbered row-major from 1 to {cell_count}.
- If action=tap, you MUST provide region in [1, {cell_count}].
- If tap target spans multiple regions, provide "regions" with all touched regions.
- For long bars (search/input/message bar), include all covered columns in that row via "regions".
- Keep output compact: no explanation, no markdown, no extra keys.
- If action=ime_input, provide text and enterAction.
- If action=swipe, prefer startRegion/endRegion or direction.
- If unsure, choose wait with 1-2 seconds.
""".strip()


def _stage2_prompt(task: str, target: str, region_desc: str) -> str:
    return f"""
Task: {task}
Target to click: {target}
Current image is merged crop from {region_desc}.

Return JSON only:
{{"x_ratio": 0-1, "y_ratio": 0-1, "confidence": 0-1, "reason": "short"}}
Where x_ratio/y_ratio are normalized inside this crop.
""".strip()


def _regions_repair_prompt(task: str, target: str, rows: int, cols: int) -> str:
    total = rows * cols
    return f"""
Task: {task}
Tap target: {target}
Grid: rows={rows}, cols={cols}, totalRegions={total}
Return JSON only:
{{"regions":[1,2,3]}}
Rules:
- Output only one key: regions.
- Regions must be integers in [1, {total}], row-major indexing.
- Keep answer very short, no markdown, no explanation.
""".strip()


def _has_unclosed_regions_array(raw: str) -> bool:
    m = re.search(r'"regions"\s*:\s*\[', raw, flags=re.IGNORECASE)
    if not m:
        return False
    return "]" not in raw[m.end() :]


def _summary_lines(records: list[dict[str, Any]]) -> str:
    if not records:
        return "(none)"
    parts = []
    for r in records[:8]:
        parts.append(
            f"step={r['step']} action={r['action']} target={r['target']} "
            f"region={r['region']} xy=({r['x']},{r['y']}) ok={r['ok']}"
        )
    return "\n".join(parts)


def run_controller(config: dict[str, Any], task: str, max_steps_override: int | None) -> int:
    if Image is None:
        raise ControllerError("Pillow is required. Install it via: pip install pillow")

    actl_cfg = config["actl"]
    vlm_cfg = config["vlm"]
    vlm_stage1_cfg = config.get("vlm_stage1", {})
    ctl_cfg = config.get("controller", {})

    client = DroidNodeClient(
        base_url=actl_cfg["base_url"],
        timeout_sec=int(actl_cfg.get("timeout_sec", 20)),
    )
    # Stage-2 (region offset) uses primary VLM config by default.
    vlm_stage2 = _build_vlm_client(vlm_cfg)
    # Stage-1 (region selection) can use a different model via vlm_stage1.
    # If vlm_stage1 is partial, missing fields inherit from primary vlm config.
    vlm_stage1 = _build_vlm_client(vlm_stage1_cfg, fallback=vlm_cfg)
    memory = MemoryStore(Path(ctl_cfg.get("memory_db_path", "clients/llm-controller/memory.db")))
    max_steps = int(max_steps_override if max_steps_override is not None else ctl_cfg.get("max_steps", 20))
    wait_after_action = float(ctl_cfg.get("wait_after_action_sec", 0.35))
    default_wait = int(ctl_cfg.get("default_wait_sec", 2))
    memory_limit = int(ctl_cfg.get("memory_recent_limit", 12))
    grid_min_segments = max(3, int(ctl_cfg.get("grid_min_segments", 3)))
    grid_max_segments = max(grid_min_segments, int(ctl_cfg.get("grid_max_segments", 12)))
    grid_target_log_error = float(ctl_cfg.get("grid_target_log_error", 0.2))

    click_range = client.get_click_range()
    print(f"[controller] target={actl_cfg['base_url']} task={task}")
    print(f"[controller] clickRange={click_range}")
    print("[controller] mode=screenshot-only, two-stage region->offset")
    print(f"[controller] model.stage1={vlm_stage1.model}")
    print(f"[controller] model.stage2={vlm_stage2.model}")

    history: list[str] = []
    for step in range(1, max_steps + 1):
        screen = client.screenshot_png()
        screen_hash = hashlib.sha256(screen).hexdigest()[:16]
        image = Image.open(io.BytesIO(screen)).convert("RGB")
        width, height = image.size
        rows, cols, cells, cell_aspect, cell_log_error = _choose_adaptive_grid(
            width,
            height,
            min_segments=grid_min_segments,
            max_segments=grid_max_segments,
            target_log_error=grid_target_log_error,
        )

        memory_lines = _summary_lines(memory.recent_for_task(task, memory_limit))
        history_lines = "\n".join(history[-8:]) if history else "(none)"

        # Planner and stage-1 are merged in a single call: action + region.
        planner_raw = vlm_stage1.ask(
            _planner_prompt(task, step, max_steps, rows, cols, memory_lines, history_lines),
            [screen],
        )
        planner = _extract_json(planner_raw) or _extract_planner_fallback(planner_raw)
        action = str(planner.get("action", "wait")).strip().lower()
        target = str(planner.get("target", "")).strip()
        if action in ("type", "input", "text", "ime", "imeinput"):
            action = "ime_input"
        print(f"\n[step {step}] planner={planner_raw}")
        print(
            f"[step {step}] grid={rows}x{cols} cells={cells} cellAspect={cell_aspect:.3f} logErr={cell_log_error:.3f}"
        )
        if vlm_stage1.last_finish_reason:
            print(f"[step {step}] planner.finish_reason={vlm_stage1.last_finish_reason}")
        print(f"[step {step}] parsed action={action} target={target!r}")

        if action == "finish":
            msg = str(planner.get("finishMessage", "done"))
            memory.add_event(task, step, screen_hash, "finish", target, None, None, None, True, msg)
            print(f"[finish] {msg}")
            return 0

        if action == "ime_input":
            text = str(planner.get("text", "")).strip()
            enter_action = str(planner.get("enterAction", "none")).lower()
            press_enter_raw = planner.get("pressEnter")
            press_enter = (
                bool(press_enter_raw)
                if isinstance(press_enter_raw, bool)
                else (enter_action != "none")
            )
            if not text:
                text = "app test"
            ok, body = client.input_text(text, enter_action=enter_action, press_enter=press_enter)
            memory.add_event(task, step, screen_hash, "ime_input", text, None, None, None, ok, body)
            history.append(f"step={step} ime_input text={text!r} enter={enter_action} ok={ok}")
            print(f"[exec] ime_input enter={enter_action} ok={ok} body={body[:240]}")
            time.sleep(wait_after_action)
            continue

        if action == "swipe":
            duration_ms = _clamp(_safe_int(planner.get("durationMs", 260), 260), 50, 5000)

            start_x = planner.get("startX")
            start_y = planner.get("startY")
            end_x = planner.get("endX")
            end_y = planner.get("endY")
            if all(v is not None for v in (start_x, start_y, end_x, end_y)):
                sx = _safe_int(start_x, width // 2)
                sy = _safe_int(start_y, int(height * 0.72))
                ex = _safe_int(end_x, width // 2)
                ey = _safe_int(end_y, int(height * 0.32))
                mode = "absolute"
            else:
                direction = str(planner.get("direction", "")).lower().strip()
                if direction in ("up", "down", "left", "right"):
                    cx = width // 2
                    cy = height // 2
                    if direction == "up":
                        sx, sy, ex, ey = cx, int(height * 0.72), cx, int(height * 0.32)
                    elif direction == "down":
                        sx, sy, ex, ey = cx, int(height * 0.32), cx, int(height * 0.72)
                    elif direction == "left":
                        sx, sy, ex, ey = int(width * 0.75), cy, int(width * 0.25), cy
                    else:
                        sx, sy, ex, ey = int(width * 0.25), cy, int(width * 0.75), cy
                    mode = f"direction:{direction}"
                else:
                    start_region = _clamp(_safe_int(planner.get("startRegion", 8), 8), 1, cells)
                    end_region = _clamp(_safe_int(planner.get("endRegion", 2), 2), 1, cells)
                    sxr = _safe_float(planner.get("startXRatio", 0.5), 0.5)
                    syr = _safe_float(planner.get("startYRatio", 0.5), 0.5)
                    exr = _safe_float(planner.get("endXRatio", 0.5), 0.5)
                    eyr = _safe_float(planner.get("endYRatio", 0.5), 0.5)
                    sx, sy = _point_from_region_ratio(start_region, rows, cols, width, height, sxr, syr)
                    ex, ey = _point_from_region_ratio(end_region, rows, cols, width, height, exr, eyr)
                    mode = f"region:{start_region}->{end_region}"

            sx = _clamp(sx, click_range.x_min, click_range.x_max)
            sy = _clamp(sy, click_range.y_min, click_range.y_max)
            ex = _clamp(ex, click_range.x_min, click_range.x_max)
            ey = _clamp(ey, click_range.y_min, click_range.y_max)

            ok, body = client.swipe(sx, sy, ex, ey, duration_ms=duration_ms)
            swipe_target = str(planner.get("swipeTarget", "")).strip()
            detail = f"planner={planner_raw[:220]} | mode={mode} | dur={duration_ms}"
            memory.add_event(task, step, screen_hash, "swipe", swipe_target, None, sx, sy, ok, detail + " | " + body[:160])
            history.append(
                f"step={step} swipe mode={mode} start=({sx},{sy}) end=({ex},{ey}) dur={duration_ms} ok={ok}"
            )
            print(f"[exec] swipe mode={mode} ({sx},{sy})->({ex},{ey}) dur={duration_ms} ok={ok} body={body[:240]}")
            time.sleep(wait_after_action)
            continue

        if action == "tap":
            if not target:
                target = "intended control"

            parsed_regions = _parse_regions_value(planner.get("regions"))
            need_regions_repair = (
                (vlm_stage1.last_finish_reason == "length" or _has_unclosed_regions_array(planner_raw))
                and len(parsed_regions) <= 1
            )
            if need_regions_repair:
                repair_raw = vlm_stage1.ask(_regions_repair_prompt(task, target, rows, cols), [screen])
                repair_obj = _extract_json(repair_raw) or _extract_planner_fallback(repair_raw)
                repair_regions = _parse_regions_value(repair_obj.get("regions"))
                if repair_regions:
                    merged = sorted(set(parsed_regions + repair_regions))
                    planner["regions"] = merged
                    if "region" not in planner and merged:
                        planner["region"] = merged[0]
                    print(f"[planner-regions-repair] raw={repair_raw}")
                    print(f"[planner-regions-repair] merged={merged}")

            input_regions, merged_regions, box, rect_meta = _rect_cover_from_regions(
                planner=planner,
                rows=rows,
                cols=cols,
                width=width,
                height=height,
            )
            left, top, right, bottom = box
            crop = image.crop(box)
            crop_png = _to_png_bytes(crop)
            region_desc = (
                f"regions={input_regions}, rectRegions={merged_regions}, "
                f"rowRange={rect_meta[0]}-{rect_meta[1]}, colRange={rect_meta[2]}-{rect_meta[3]}"
            )

            raw_stage2 = vlm_stage2.ask(_stage2_prompt(task, target, region_desc), [crop_png])
            obj_stage2 = _extract_json(raw_stage2) or {}
            xr = _safe_float(obj_stage2.get("x_ratio", 0.5), 0.5)
            yr = _safe_float(obj_stage2.get("y_ratio", 0.5), 0.5)
            xr = max(0.0, min(1.0, xr))
            yr = max(0.0, min(1.0, yr))
            x = int(left + xr * max(1, (right - left - 1)))
            y = int(top + yr * max(1, (bottom - top - 1)))
            x = _clamp(x, click_range.x_min, click_range.x_max)
            y = _clamp(y, click_range.y_min, click_range.y_max)

            ok, body = client.click(x, y)
            detail = (
                f"planner={planner_raw[:160]} | s2={raw_stage2[:160]} | "
                f"inputRegions={input_regions} mergedRegions={merged_regions} box={box} ratio=({xr:.3f},{yr:.3f})"
            )
            primary_region = merged_regions[0] if merged_regions else input_regions[0]
            memory.add_event(task, step, screen_hash, "tap", target, primary_region, x, y, ok, detail + " | " + body[:160])
            history.append(
                f"step={step} tap target={target!r} inputRegions={input_regions} mergedRegions={merged_regions} "
                f"ratio=({xr:.2f},{yr:.2f}) xy=({x},{y}) ok={ok}"
            )
            print(f"[planner-regions] input={input_regions} mergedRect={merged_regions} box={box}")
            if len(input_regions) == 1 and len(merged_regions) > 1:
                print("[planner-regions] auto-expanded single region to row rectangle for bar-like target")
            print(f"[stage2] {raw_stage2}")
            print(f"[exec] tap xy=({x},{y}) ok={ok} body={body[:240]}")
            time.sleep(wait_after_action)
            continue

        wait_sec = _safe_int(planner.get("waitSec", default_wait), default_wait)
        wait_sec = _clamp(wait_sec, 1, 10)
        memory.add_event(task, step, screen_hash, "wait", target, None, None, None, True, f"wait {wait_sec}s")
        history.append(f"step={step} wait {wait_sec}s")
        print(f"[exec] wait {wait_sec}s")
        time.sleep(wait_sec)

    print("[finish] max steps reached without finish action")
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(description="DroidNode VLM-only controller (Python PoC)")
    parser.add_argument("--config", required=True, help="path to config json")
    parser.add_argument("--task", required=True, help="high-level task")
    parser.add_argument("--max-steps", type=int, default=None)
    args = parser.parse_args()

    with open(args.config, "r", encoding="utf-8") as f:
        config = json.load(f)
    return run_controller(config, args.task, args.max_steps)


if __name__ == "__main__":
    raise SystemExit(main())
