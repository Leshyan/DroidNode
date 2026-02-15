#!/usr/bin/env python3

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime
from typing import Any


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    if p <= 0:
        return min(values)
    if p >= 100:
        return max(values)
    sorted_values = sorted(values)
    k = (len(sorted_values) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_values) - 1)
    if f == c:
        return sorted_values[f]
    return sorted_values[f] + (k - f) * (sorted_values[c] - sorted_values[f])


ENDPOINTS: list[dict[str, Any]] = [
    {
        "id": "health",
        "name": "Health",
        "method": "GET",
        "path": "/v1/health",
        "payload": None,
        "validator": "json_code0",
    },
    {
        "id": "system_info",
        "name": "System Info",
        "method": "GET",
        "path": "/v1/system/info",
        "payload": None,
        "validator": "json_code0",
    },
    {
        "id": "click",
        "name": "Click",
        "method": "POST",
        "path": "/v1/control/click",
        "payload": {"x": 300, "y": 800},
        "validator": "json_code0",
    },
    {
        "id": "swipe",
        "name": "Swipe",
        "method": "POST",
        "path": "/v1/control/swipe",
        "payload": {"startX": 300, "startY": 1200, "endX": 300, "endY": 900, "durationMs": 120},
        "validator": "json_code0",
    },
    {
        "id": "input",
        "name": "Input",
        "method": "POST",
        "path": "/v1/control/input",
        "payload": {"text": "perf-test", "pressEnter": False, "enterAction": "none"},
        "validator": "json_code0",
    },
    {
        "id": "ui_xml",
        "name": "UI XML",
        "method": "POST",
        "path": "/v1/ui/xml",
        "payload": {},
        "validator": "xml",
    },
    {
        "id": "screenshot_post",
        "name": "Screenshot POST",
        "method": "POST",
        "path": "/v1/ui/screenshot",
        "payload": {},
        "validator": "image_stream",
    },
]


def make_request(url: str, method: str, payload: Any, timeout: float) -> dict[str, Any]:
    headers: dict[str, str] = {}
    data: bytes | None = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url=url, data=data, method=method, headers=headers)

    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            content_type = resp.headers.get("Content-Type", "")
            parsed = None
            parse_error = ""
            if "application/json" in content_type.lower():
                try:
                    parsed = json.loads(body.decode("utf-8"))
                except Exception as e:  # noqa: BLE001
                    parse_error = str(e)
            return {
                "ok_transport": True,
                "http_status": int(resp.status),
                "latency_ms": elapsed_ms,
                "content_type": content_type,
                "resp_bytes": len(body),
                "body_preview": body[:220].decode("utf-8", errors="replace"),
                "json": parsed,
                "parse_error": parse_error,
                "error": "",
                "raw_body": body,
            }
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b""
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        parsed = None
        parse_error = ""
        content_type = e.headers.get("Content-Type", "") if e.headers else ""
        if "application/json" in content_type.lower() or body.startswith(b"{"):
            try:
                parsed = json.loads(body.decode("utf-8"))
            except Exception as ex:  # noqa: BLE001
                parse_error = str(ex)
        return {
            "ok_transport": True,
            "http_status": int(e.code),
            "latency_ms": elapsed_ms,
            "content_type": content_type,
            "resp_bytes": len(body),
            "body_preview": body[:220].decode("utf-8", errors="replace"),
            "json": parsed,
            "parse_error": parse_error,
            "error": f"HTTPError: {e}",
            "raw_body": body,
        }
    except Exception as e:  # noqa: BLE001
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return {
            "ok_transport": False,
            "http_status": 0,
            "latency_ms": elapsed_ms,
            "content_type": "",
            "resp_bytes": 0,
            "body_preview": "",
            "json": None,
            "parse_error": "",
            "error": str(e),
            "raw_body": b"",
        }


def validate_result(case: dict[str, Any], result: dict[str, Any]) -> tuple[bool, str, int, int | None]:
    if not result.get("ok_transport", False):
        return False, f"transport error: {result.get(error, )}", -1, None

    status = int(result.get("http_status", 0))
    if status != 200:
        return False, f"http {status}", -1, None

    validator = case["validator"]
    content_type = str(result.get("content_type", "")).lower()
    body: bytes = result.get("raw_body", b"")

    if validator == "json_code0":
        j = result.get("json")
        if not isinstance(j, dict):
            return False, "json missing", -1, None
        code = j.get("code")
        if code != 0:
            return False, f"app code {code}", int(code) if isinstance(code, int) else -1, None
        return True, "ok", int(code), None

    if validator == "xml":
        if "xml" not in content_type:
            return False, f"content-type {content_type}", -1, None
        trimmed = body.lstrip()
        if not trimmed.startswith(b"<?xml"):
            return False, "xml marker missing", -1, None
        return True, "ok", 0, len(trimmed)

    if validator == "image_stream":
        if "image/png" not in content_type:
            return False, f"content-type {content_type}", -1, None
        if len(body) <= 0:
            return False, "empty image", -1, None
        if len(body) >= 8 and body[:8] != b"\x89PNG\r\n\x1a\n":
            return False, "png signature mismatch", -1, None
        return True, "ok", 0, len(body)

    return False, f"unknown validator {validator}", -1, None


def calc_stats(samples: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(samples)
    success_samples = [s for s in samples if s["ok"]]
    fail_samples = [s for s in samples if not s["ok"]]

    lat_all = [float(s["latency_ms"]) for s in samples]
    lat_ok = [float(s["latency_ms"]) for s in success_samples]

    def lat_summary(values: list[float]) -> dict[str, float]:
        if not values:
            return {
                "min": 0.0,
                "mean": 0.0,
                "p50": 0.0,
                "p90": 0.0,
                "p95": 0.0,
                "p99": 0.0,
                "max": 0.0,
            }
        return {
            "min": min(values),
            "mean": statistics.mean(values),
            "p50": percentile(values, 50),
            "p90": percentile(values, 90),
            "p95": percentile(values, 95),
            "p99": percentile(values, 99),
            "max": max(values),
        }

    resp_sizes = [int(s["resp_bytes"]) for s in success_samples]
    image_sizes = [int(s["image_bytes"]) for s in success_samples if isinstance(s.get("image_bytes"), int)]

    def size_summary(values: list[int]) -> dict[str, float | int]:
        if not values:
            return {"avg": 0.0, "min": 0, "max": 0}
        return {
            "avg": statistics.mean(values),
            "min": min(values),
            "max": max(values),
        }

    status_counts = Counter(str(s["http_status"]) for s in samples)
    failure_reasons = Counter(s["reason"] for s in fail_samples)

    return {
        "total": total,
        "success": len(success_samples),
        "fail": len(fail_samples),
        "success_rate": (len(success_samples) / total * 100.0) if total else 0.0,
        "latency_all_ms": lat_summary(lat_all),
        "latency_success_ms": lat_summary(lat_ok),
        "response_bytes": size_summary(resp_sizes),
        "image_bytes": size_summary(image_sizes),
        "status_counts": dict(status_counts),
        "failure_reasons": dict(failure_reasons),
    }


def run_case(base_url: str, case: dict[str, Any], warmup: int, samples: int, timeout: float, interval_ms: int, verbose: bool) -> dict[str, Any]:
    url = f"{base_url}{case['path']}"

    for i in range(warmup):
        _ = make_request(url, case["method"], case["payload"], timeout)
        if verbose:
            print(f"[warmup {case['id']} {i + 1}/{warmup}] done")

    rows: list[dict[str, Any]] = []
    t0 = time.perf_counter()
    for idx in range(samples):
        result = make_request(url, case["method"], case["payload"], timeout)
        ok, reason, app_code, image_bytes = validate_result(case, result)
        row = {
            "index": idx + 1,
            "ok": ok,
            "reason": reason,
            "http_status": int(result.get("http_status", 0)),
            "app_code": app_code,
            "latency_ms": float(result.get("latency_ms", 0.0)),
            "resp_bytes": int(result.get("resp_bytes", 0)),
            "image_bytes": image_bytes,
            "content_type": result.get("content_type", ""),
            "error": result.get("error", ""),
            "body_preview": result.get("body_preview", "") if not ok else "",
        }
        rows.append(row)

        if verbose:
            print(
                f"[{case['id']} {idx + 1:03d}] ok={ok} http={row['http_status']} "
                f"lat={row['latency_ms']:.2f}ms resp={row['resp_bytes']}B reason={reason}"
            )

        if interval_ms > 0 and idx != samples - 1:
            time.sleep(interval_ms / 1000.0)

    elapsed = time.perf_counter() - t0
    stats = calc_stats(rows)
    stats["elapsed_seconds"] = elapsed
    stats["req_per_sec"] = (samples / elapsed) if elapsed > 0 else 0.0

    return {
        "id": case["id"],
        "name": case["name"],
        "method": case["method"],
        "path": case["path"],
        "url": url,
        "validator": case["validator"],
        "warmup": warmup,
        "samples": samples,
        "stats": stats,
        "raw": rows,
    }


def print_case_summary(case_result: dict[str, Any]) -> None:
    s = case_result["stats"]
    lat = s["latency_success_ms"]
    print("------------------------------------------------------------")
    print(f"[{case_result['name']}] {case_result['method']} {case_result['path']}")
    print(
        f"  success={s['success']}/{s['total']} ({s['success_rate']:.2f}%) "
        f"fail={s['fail']} req/s={s['req_per_sec']:.2f}"
    )
    print(
        f"  latency(ms): mean={lat['mean']:.2f} p50={lat['p50']:.2f} "
        f"p90={lat['p90']:.2f} p95={lat['p95']:.2f} p99={lat['p99']:.2f}"
    )
    rb = s["response_bytes"]
    print(f"  response bytes: avg={rb['avg']:.1f} min={rb['min']} max={rb['max']}")
    ib = s["image_bytes"]
    if ib["max"] > 0:
        print(f"  image bytes   : avg={ib['avg']:.1f} min={ib['min']} max={ib['max']}")
    if s["failure_reasons"]:
        print(f"  failures      : {s['failure_reasons']}")


def write_markdown_report(path: str, host: str, port: int, warmup: int, samples: int, interval_ms: int, timeout: float, results: list[dict[str, Any]], total_elapsed: float) -> None:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines: list[str] = []

    lines.append("# DroidNode API Performance Report")
    lines.append("")
    lines.append(f"- Generated: {generated}")
    lines.append(f"- Target: `http://{host}:{port}`")
    lines.append(f"- Config: warmup={warmup}, samples={samples}, intervalMs={interval_ms}, timeout={timeout}s")
    lines.append(f"- Total wall time: {total_elapsed:.3f}s")
    lines.append("")

    lines.append("## Endpoint Summary")
    lines.append("")
    lines.append("| API | Method | Success | Mean (ms) | P50 | P90 | P95 | P99 | Resp Avg (B) | Image Avg (B) |")
    lines.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in results:
        s = r["stats"]
        lat = s["latency_success_ms"]
        rb = s["response_bytes"]
        ib = s["image_bytes"]
        lines.append(
            "| "
            f"`{r['path']}` | {r['method']} | {s['success_rate']:.2f}% ({s['success']}/{s['total']})"
            f" | {lat['mean']:.2f} | {lat['p50']:.2f} | {lat['p90']:.2f} | {lat['p95']:.2f} | {lat['p99']:.2f}"
            f" | {rb['avg']:.1f} | {ib['avg']:.1f} |"
        )
    lines.append("")

    by_p95 = sorted(results, key=lambda x: x["stats"]["latency_success_ms"]["p95"], reverse=True)
    lines.append("## Bottlenecks (by P95 latency)")
    lines.append("")
    for idx, r in enumerate(by_p95[:3], start=1):
        s = r["stats"]
        lat = s["latency_success_ms"]
        lines.append(
            f"{idx}. `{r['path']}` ({r['method']}): "
            f"P95={lat['p95']:.2f}ms, P99={lat['p99']:.2f}ms, success={s['success_rate']:.2f}%"
        )
    lines.append("")

    unstable = [r for r in results if r["stats"]["fail"] > 0]
    lines.append("## Reliability")
    lines.append("")
    if not unstable:
        lines.append("- All tested APIs reached 100% success in this run.")
    else:
        for r in unstable:
            lines.append(
                f"- `{r['path']}` fail={r['stats']['fail']}/{r['stats']['total']}, "
                f"reasons={r['stats']['failure_reasons']}"
            )
    lines.append("")

    lines.append("## Reproduce")
    lines.append("")
    lines.append("```bash")
    lines.append(
        "python3 tests/api_perf_tester.py "
        f"{host} {port} --warmup {warmup} --samples {samples} "
        f"--interval-ms {interval_ms} --timeout {timeout}"
    )
    lines.append("```")
    lines.append("")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="ACTL full API performance tester")
    parser.add_argument("host", nargs="?", default="192.168.0.105")
    parser.add_argument("port", nargs="?", type=int, default=17175)
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=25.0)
    parser.add_argument("--interval-ms", type=int, default=0)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--save-json", default="")
    parser.add_argument("--write-doc", default="")
    args = parser.parse_args()

    if args.samples <= 0:
        print("samples must be > 0", file=sys.stderr)
        return 2
    if args.warmup < 0:
        print("warmup must be >= 0", file=sys.stderr)
        return 2

    base_url = f"http://{args.host}:{args.port}"
    print("------------------------------------------------------------")
    print("DroidNode Full API Performance Test")
    print(f"Target  : {base_url}")
    print(f"Warmup  : {args.warmup}")
    print(f"Samples : {args.samples}")
    print(f"Timeout : {args.timeout}s")
    print(f"APIs    : {len(ENDPOINTS)}")
    print("------------------------------------------------------------")

    all_results: list[dict[str, Any]] = []
    t0 = time.perf_counter()
    for idx, case in enumerate(ENDPOINTS, start=1):
        print(f"[run {idx}/{len(ENDPOINTS)}] {case['name']} -> {case['method']} {case['path']}")
        case_result = run_case(
            base_url=base_url,
            case=case,
            warmup=args.warmup,
            samples=args.samples,
            timeout=args.timeout,
            interval_ms=args.interval_ms,
            verbose=args.verbose,
        )
        all_results.append(case_result)
        print_case_summary(case_result)

    total_elapsed = time.perf_counter() - t0

    print("------------------------------------------------------------")
    print("Overall")
    total_req = sum(r["stats"]["total"] for r in all_results)
    total_ok = sum(r["stats"]["success"] for r in all_results)
    total_fail = sum(r["stats"]["fail"] for r in all_results)
    print(f"  requests: {total_req}")
    print(f"  success : {total_ok}")
    print(f"  fail    : {total_fail}")
    print(f"  success rate: {(total_ok / total_req * 100.0) if total_req else 0.0:.2f}%")
    print(f"  total time  : {total_elapsed:.3f}s")
    print("------------------------------------------------------------")

    if args.save_json:
        payload = {
            "meta": {
                "generated_at": datetime.now().isoformat(timespec="seconds"),
                "target": base_url,
                "host": args.host,
                "port": args.port,
                "warmup": args.warmup,
                "samples": args.samples,
                "timeout": args.timeout,
                "interval_ms": args.interval_ms,
                "total_elapsed_seconds": total_elapsed,
            },
            "results": all_results,
        }
        with open(args.save_json, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print(f"Saved raw report: {args.save_json}")

    if args.write_doc:
        write_markdown_report(
            path=args.write_doc,
            host=args.host,
            port=args.port,
            warmup=args.warmup,
            samples=args.samples,
            interval_ms=args.interval_ms,
            timeout=args.timeout,
            results=all_results,
            total_elapsed=total_elapsed,
        )
        print(f"Saved markdown report: {args.write_doc}")

    return 0 if total_fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
