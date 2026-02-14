# ACTL XML-First LLM/VLM Agent

## Purpose
- Use ACTL APIs to execute mobile operations.
- Use `/v1/ui/screenshot` + VLM only.
- When Tap has no effect, next VLM call receives both: full screenshot + local focus crop (with grid).

## Config
Edit `tools/actl_agent_config.json`:
- `llm.base_url`, `llm.api_key`, `llm.model`
- `llm.thinking`: optional, e.g. `{"type":"enabled"}` for providers like BigModel
- `vlm.base_url`, `vlm.api_key`, `vlm.model`
- `vlm.thinking`: optional, e.g. `{"type":"enabled"}`
- `actl.base_url` for your APK API service
- `agent.wait_after_tap_sec`: delay before checking tap effect
- `agent.tap_retry_on_no_change`: auto retries with offset points when UI does not change
- `agent.invalid_action_retry`: retry count for malformed/truncated model output (strict one-line recovery)
- `agent.min_tap_drift_px`: minimum drift for repeated tap in loop guard (recommended `50`)
- `agent.capture_dir`: directory to keep intermediate screenshots
- `agent.post_state_check`: fetch post-action state and write `ui_changed` into history context
- `agent.focus_radius_px`: local crop radius around ineffective tap (default `200` => +-200px)
- `agent.focus_expand_step_px`: expand radius step when local crop is near-solid (default `50`)
- `agent.focus_max_radius_px`: max local crop radius for expansion
- `agent.focus_grid_px`: grid spacing for local crop overlay (not full screenshot)
- `agent.screenshot_ahash_threshold`: screenshot similarity threshold (lower = stricter change detection)

## Optional Dependency
- Grid overlay uses Pillow. If missing:
```bash
tools/.venv/bin/pip install pillow
```

## Run
```bash
python3 tools/actl_agent.py \
  --config tools/actl_agent_config.json \
  --task "Open WeChat search and type hello" \
  --max-steps 12
```

## Supported Model Actions
- `do(action="Tap", element=[x,y])`
- `do(action="Swipe", start=[x1,y1], end=[x2,y2], durationMs=300)`
- `do(action="Type", text="xxx")`
- `do(action="Type", text="xxx", enterAction="send")`
- `do(action="Wait", duration="2 seconds")`
- `finish(message="xxx")`
