#!/usr/bin/env python3

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
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


def request_once(url: str, timeout: float) -> dict[str, Any]:
    payload = b"{}"
    req = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json"},
    )

    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            return {
                "ok_transport": True,
                "http_status": int(resp.status),
                "latency_ms": elapsed_ms,
                "content_type": resp.headers.get("Content-Type", ""),
                "resp_bytes": len(body),
                "raw_body": body,
                "error": "",
            }
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b""
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return {
            "ok_transport": True,
            "http_status": int(e.code),
            "latency_ms": elapsed_ms,
            "content_type": e.headers.get("Content-Type", "") if e.headers else "",
            "resp_bytes": len(body),
            "raw_body": body,
            "error": f"HTTPError: {e}",
        }
    except Exception as e:  # noqa: BLE001
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return {
            "ok_transport": False,
            "http_status": 0,
            "latency_ms": elapsed_ms,
            "content_type": "",
            "resp_bytes": 0,
            "raw_body": b"",
            "error": str(e),
        }


def validate_xml(result: dict[str, Any]) -> tuple[bool, str, int, int]:
    if not result.get("ok_transport"):
        return False, f"transport: {result.get('error', '')}", 0, 0

    status = int(result.get("http_status", 0))
    if status != 200:
        return False, f"http={status}", 0, 0

    content_type = str(result.get("content_type", "")).lower()
    if "xml" not in content_type:
        return False, f"content-type={content_type or '-'}", 0, 0

    raw: bytes = result.get("raw_body", b"")
    if not raw:
        return False, "empty-body", 0, 0

    trimmed = raw.lstrip()
    if not trimmed.startswith(b"<?xml"):
        return False, "xml-marker-missing", 0, len(raw)

    try:
        xml_text = trimmed.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return False, "xml-decode-failed", 0, len(raw)

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        return False, f"xml-parse-failed: {e}", 0, len(raw)

    node_count = len(root.findall(".//node"))
    return True, "ok", node_count, len(raw)


def summarize(samples: list[dict[str, Any]], url: str, total_elapsed_s: float) -> dict[str, Any]:
    total = len(samples)
    success_samples = [s for s in samples if s["ok"]]
    fail_samples = [s for s in samples if not s["ok"]]

    lat_all = [float(s["latency_ms"]) for s in samples]
    lat_ok = [float(s["latency_ms"]) for s in success_samples]
    xml_sizes = [int(s["xml_bytes"]) for s in success_samples]
    node_counts = [int(s["node_count"]) for s in success_samples]

    def stats(values: list[float]) -> dict[str, float]:
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

    failure_breakdown = Counter(s["reason"] for s in fail_samples)

    report = {
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "target": url,
        "total": total,
        "success": len(success_samples),
        "fail": len(fail_samples),
        "success_rate": (len(success_samples) / total * 100.0) if total else 0.0,
        "wall_time_s": total_elapsed_s,
        "throughput_rps": (total / total_elapsed_s) if total_elapsed_s > 0 else 0.0,
        "latency_all_ms": stats(lat_all),
        "latency_success_ms": stats(lat_ok),
        "xml_size_bytes": {
            "avg": statistics.mean(xml_sizes) if xml_sizes else 0.0,
            "min": min(xml_sizes) if xml_sizes else 0,
            "max": max(xml_sizes) if xml_sizes else 0,
        },
        "node_count": {
            "avg": statistics.mean(node_counts) if node_counts else 0.0,
            "min": min(node_counts) if node_counts else 0,
            "max": max(node_counts) if node_counts else 0,
        },
        "failure_breakdown": dict(failure_breakdown),
    }

    print("------------------------------------------------------------")
    print("DroidNode UI XML API Performance Report")
    print(f"Target      : {url}")
    print(f"Samples     : {total}")
    print(f"Success     : {report['success']}")
    print(f"Fail        : {report['fail']}")
    print(f"SuccessRate : {report['success_rate']:.2f}%")
    print(f"WallTime    : {report['wall_time_s']:.3f}s")
    print(f"Req/s       : {report['throughput_rps']:.2f}")
    print("------------------------------------------------------------")

    for label, key in (("Latency (all requests, ms)", "latency_all_ms"), ("Latency (success only, ms)", "latency_success_ms")):
        s = report[key]
        print(label)
        print(f"  min   : {s['min']:.2f}")
        print(f"  mean  : {s['mean']:.2f}")
        print(f"  p50   : {s['p50']:.2f}")
        print(f"  p90   : {s['p90']:.2f}")
        print(f"  p95   : {s['p95']:.2f}")
        print(f"  p99   : {s['p99']:.2f}")
        print(f"  max   : {s['max']:.2f}")
        print("------------------------------------------------------------")

    print("XML Payload (success only)")
    print(f"  bytes avg : {report['xml_size_bytes']['avg']:.1f}")
    print(f"  bytes min : {report['xml_size_bytes']['min']}")
    print(f"  bytes max : {report['xml_size_bytes']['max']}")
    print("------------------------------------------------------------")

    print("UI Node Count (success only)")
    print(f"  avg : {report['node_count']['avg']:.1f}")
    print(f"  min : {report['node_count']['min']}")
    print(f"  max : {report['node_count']['max']}")
    print("------------------------------------------------------------")

    if report["failure_breakdown"]:
        print("Failure Breakdown")
        for reason, count in sorted(report["failure_breakdown"].items(), key=lambda x: x[1], reverse=True):
            print(f"  {count}x {reason}")
        print("------------------------------------------------------------")

    return report


def to_markdown(report: dict[str, Any], warmup: int, samples: int, timeout: float, interval_ms: int) -> str:
    la = report["latency_all_ms"]
    ls = report["latency_success_ms"]
    xb = report["xml_size_bytes"]
    nc = report["node_count"]

    lines = [
        "# DroidNode UI XML API Performance Report",
        "",
        f"- Generated: {report['generated_at']}",
        f"- Target: `{report['target']}`",
        f"- Config: warmup={warmup}, samples={samples}, intervalMs={interval_ms}, timeout={timeout:.1f}s",
        f"- Success: {report['success']}/{report['total']} ({report['success_rate']:.2f}%)",
        f"- Total wall time: {report['wall_time_s']:.3f}s",
        "",
        "## Latency",
        "",
        "| Scope | Mean (ms) | P50 | P90 | P95 | P99 |",
        "|---|---:|---:|---:|---:|---:|",
        f"| All | {la['mean']:.2f} | {la['p50']:.2f} | {la['p90']:.2f} | {la['p95']:.2f} | {la['p99']:.2f} |",
        f"| Success | {ls['mean']:.2f} | {ls['p50']:.2f} | {ls['p90']:.2f} | {ls['p95']:.2f} | {ls['p99']:.2f} |",
        "",
        "## Payload",
        "",
        f"- XML bytes avg/min/max: {xb['avg']:.1f} / {xb['min']} / {xb['max']}",
        f"- UI node avg/min/max: {nc['avg']:.1f} / {nc['min']} / {nc['max']}",
    ]

    fb = report.get("failure_breakdown", {})
    if fb:
        lines.extend(["", "## Failure Breakdown", ""])
        for reason, count in sorted(fb.items(), key=lambda x: x[1], reverse=True):
            lines.append(f"- {count}x `{reason}`")

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="DroidNode /v1/ui/xml performance tester")
    parser.add_argument("host", nargs="?", default="192.168.0.101")
    parser.add_argument("port", nargs="?", type=int, default=17175)
    parser.add_argument("--path", default="/v1/ui/xml")
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--interval-ms", type=int, default=0)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--save-json", default="", help="save raw result json")
    parser.add_argument("--save-md", default="", help="save markdown summary")
    args = parser.parse_args()

    if args.samples <= 0:
        print("samples must be > 0", file=sys.stderr)
        return 2
    if args.warmup < 0:
        print("warmup must be >= 0", file=sys.stderr)
        return 2
    if args.timeout <= 0:
        print("timeout must be > 0", file=sys.stderr)
        return 2

    url = f"http://{args.host}:{args.port}{args.path}"

    print("------------------------------------------------------------")
    print("DroidNode UI XML API Performance Test")
    print(f"Target : {url}")
    print(f"Warmup : {args.warmup}")
    print(f"Sample : {args.samples}")
    print("------------------------------------------------------------")

    for i in range(args.warmup):
        _ = request_once(url, args.timeout)
        if args.verbose:
            print(f"[warmup {i + 1}/{args.warmup}] done")

    samples: list[dict[str, Any]] = []
    t0 = time.perf_counter()
    for i in range(args.samples):
        res = request_once(url, args.timeout)
        ok, reason, node_count, xml_bytes = validate_xml(res)
        item = {
            "index": i + 1,
            "ok": ok,
            "reason": reason,
            "node_count": node_count,
            "xml_bytes": xml_bytes,
            "latency_ms": res["latency_ms"],
            "http_status": res["http_status"],
            "content_type": res["content_type"],
            "resp_bytes": res["resp_bytes"],
            "error": res["error"],
        }
        samples.append(item)

        if args.verbose:
            print(
                f"[{item['index']:03d}] status={item['http_status']} "
                f"lat={item['latency_ms']:.2f}ms size={item['resp_bytes']}B "
                f"node={item['node_count']} ok={item['ok']} reason={item['reason']}"
            )

        if args.interval_ms > 0 and i != args.samples - 1:
            time.sleep(args.interval_ms / 1000.0)

    elapsed = time.perf_counter() - t0
    report = summarize(samples, url, elapsed)

    if args.save_json:
        payload = {
            "report": report,
            "config": {
                "target": url,
                "warmup": args.warmup,
                "samples": args.samples,
                "interval_ms": args.interval_ms,
                "timeout": args.timeout,
            },
            "samples": samples,
        }
        with open(args.save_json, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print(f"Saved raw results: {args.save_json}")

    if args.save_md:
        content = to_markdown(report, args.warmup, args.samples, args.timeout, args.interval_ms)
        with open(args.save_md, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"Saved markdown report: {args.save_md}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
