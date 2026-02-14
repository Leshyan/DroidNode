# LLM Controller (Python PoC)

This directory contains a Python validation controller for DroidNode APIs.

## Goals

- Pure screenshot-driven automation (no XML parsing).
- Two-stage tap localization to reduce direct absolute-coordinate errors:
1. Stage-1: VLM picks one or more regions in an adaptive `m*n` grid.
2. Stage-2: VLM predicts offset inside that region.
- SQLite-based persistent memory for step history.

## Files

- `controller.py`: runnable PoC controller.
- `config.example.json`: config template.

## Setup

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install pillow
cp clients/llm-controller/config.example.json clients/llm-controller/config.json
# edit clients/llm-controller/config.json: actl.base_url / vlm.api_key / vlm.model
```

## Run

```bash
python3 clients/llm-controller/controller.py \
  --config clients/llm-controller/config.json \
  --task "打开QQ给曦兮发消息：app test"
```

## Notes

- Controller currently uses VLM only.
- Supported actions: `tap`, `swipe`, `ime_input`, `wait`, `finish`.
- Model split:
  - `vlm_stage1`: Planner + Stage-1 region selection (single call).
  - `vlm`: Stage-2 offset prediction.
  - If `vlm_stage1` is missing fields, they inherit from `vlm`.
- Adaptive grid:
  - No fixed 3x3.
  - Per screenshot, controller picks `m x n` (`m,n >= 3`) so each cell is as close to 1:1 as possible while keeping `m*n` small.
  - Tunables in config: `grid_min_segments`, `grid_max_segments`, `grid_target_log_error`.
- Multi-region tap crop:
  - Planner may return `regions` for a target spanning multiple cells.
  - Controller auto-expands to the minimal covering rectangle (if non-rect, fills missing cells), crops once, and computes absolute tap from that merged crop.
  - If planner output is truncated (`finish_reason=length`) and `regions` is incomplete, controller does a short regions-only repair call before Stage-2.
- Tap path uses 2 model calls total: `planner+region` -> `stage2 offset`.
- Screenshot API expected: `POST /v1/ui/screenshot` returning `image/png`.
