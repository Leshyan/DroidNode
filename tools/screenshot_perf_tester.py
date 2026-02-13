#!/usr/bin/env python3

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
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
            latency_ms = (time.perf_counter() - start) * 1000.0
            status = int(resp.status)
            content_type = resp.headers.get("Content-Type", "")
            parsed = None
            parse_error = None
            if "application/json" in content_type.lower():
                try:
                    parsed = json.loads(body.decode("utf-8"))
                except Exception as e:
                    parse_error = str(e)
            return {
                "ok_transport": True,
                "latency_ms": latency_ms,
                "http_status": status,
                "content_type": content_type,
                "resp_bytes": len(body),
                "json": parsed,
                "parse_error": parse_error,
                "error": "",
            }
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b""
        latency_ms = (time.perf_counter() - start) * 1000.0
        parsed = None
        parse_error = None
        try:
            parsed = json.loads(body.decode("utf-8"))
        except Exception as ex:
            parse_error = str(ex)
        return {
            "ok_transport": True,
            "latency_ms": latency_ms,
            "http_status": int(e.code),
            "content_type": e.headers.get("Content-Type", "") if e.headers else "",
            "resp_bytes": len(body),
            "json": parsed,
            "parse_error": parse_error,
            "error": f"HTTPError: {e}",
        }
    except Exception as e:
        latency_ms = (time.perf_counter() - start) * 1000.0
        return {
            "ok_transport": False,
            "latency_ms": latency_ms,
            "http_status": 0,
            "content_type": "",
            "resp_bytes": 0,
            "json": None,
            "parse_error": "",
            "error": str(e),
        }


def app_success(result: dict[str, Any]) -> bool:
    if not result["ok_transport"]:
        return False
    if result["http_status"] != 200:
        return False
    content_type = str(result.get("content_type", "")).lower()
    if content_type.startswith("image/"):
        return result.get("resp_bytes", 0) > 0

    j = result.get("json")
    if not isinstance(j, dict):
        return False
    if j.get("code") != 0:
        return False
    data = j.get("data")
    if not isinstance(data, dict):
        return False
    bytes_count = data.get("bytes")
    image_b64 = data.get("imageBase64")
    if not isinstance(bytes_count, int) or bytes_count <= 0:
        return False
    if not isinstance(image_b64, str) or not image_b64:
        return False
    return True


def summarize(samples: list[dict[str, Any]], url: str, total_elapsed_s: float) -> None:
    total = len(samples)
    success_samples = [s for s in samples if app_success(s)]
    success_count = len(success_samples)
    fail_count = total - success_count

    lat_all = [s["latency_ms"] for s in samples]
    lat_ok = [s["latency_ms"] for s in success_samples]

    resp_sizes = [s["resp_bytes"] for s in success_samples]
    image_sizes = []
    for s in success_samples:
        content_type = str(s.get("content_type", "")).lower()
        if content_type.startswith("image/"):
            image_sizes.append(s.get("resp_bytes", 0))
            continue
        j = s.get("json") or {}
        data = j.get("data") if isinstance(j, dict) else None
        if isinstance(data, dict) and isinstance(data.get("bytes"), int):
            image_sizes.append(data["bytes"])

    error_keys = Counter()
    for s in samples:
        if app_success(s):
            continue
        j = s.get("json")
        code = "-"
        msg = s.get("error", "")
        if isinstance(j, dict):
            code = str(j.get("code", "-"))
            msg = str(j.get("message", msg))
        key = f"http={s['http_status']} code={code} msg={msg}"
        error_keys[key] += 1

    print("------------------------------------------------------------")
    print("ACTL Screenshot API Performance Report")
    print(f"Target      : {url}")
    print(f"Samples     : {total}")
    print(f"Success     : {success_count}")
    print(f"Fail        : {fail_count}")
    print(f"SuccessRate : {(success_count / total * 100.0) if total else 0.0:.2f}%")
    print(f"WallTime    : {total_elapsed_s:.3f}s")
    print(f"Req/s       : {(total / total_elapsed_s) if total_elapsed_s > 0 else 0.0:.2f}")
    print("------------------------------------------------------------")

    if lat_all:
        print("Latency (all requests, ms)")
        print(f"  min   : {min(lat_all):.2f}")
        print(f"  mean  : {statistics.mean(lat_all):.2f}")
        print(f"  p50   : {percentile(lat_all, 50):.2f}")
        print(f"  p90   : {percentile(lat_all, 90):.2f}")
        print(f"  p95   : {percentile(lat_all, 95):.2f}")
        print(f"  p99   : {percentile(lat_all, 99):.2f}")
        print(f"  max   : {max(lat_all):.2f}")
        print("------------------------------------------------------------")

    if lat_ok:
        print("Latency (successful requests only, ms)")
        print(f"  min   : {min(lat_ok):.2f}")
        print(f"  mean  : {statistics.mean(lat_ok):.2f}")
        print(f"  p50   : {percentile(lat_ok, 50):.2f}")
        print(f"  p90   : {percentile(lat_ok, 90):.2f}")
        print(f"  p95   : {percentile(lat_ok, 95):.2f}")
        print(f"  p99   : {percentile(lat_ok, 99):.2f}")
        print(f"  max   : {max(lat_ok):.2f}")
        print("------------------------------------------------------------")

    if resp_sizes:
        print("Response Size (successful requests)")
        print(f"  body bytes avg : {statistics.mean(resp_sizes):.1f}")
        print(f"  body bytes min : {min(resp_sizes)}")
        print(f"  body bytes max : {max(resp_sizes)}")
        print("------------------------------------------------------------")

    if image_sizes:
        print("Image Size (successful requests)")
        print(f"  png bytes avg  : {statistics.mean(image_sizes):.1f}")
        print(f"  png bytes min  : {min(image_sizes)}")
        print(f"  png bytes max  : {max(image_sizes)}")
        print("------------------------------------------------------------")

    if error_keys:
        print("Failure Breakdown")
        for key, count in error_keys.most_common():
            print(f"  {count}x {key}")
        print("------------------------------------------------------------")


def main() -> int:
    parser = argparse.ArgumentParser(description="ACTL screenshot API performance tester")
    parser.add_argument("host", nargs="?", default="192.168.0.105")
    parser.add_argument("port", nargs="?", type=int, default=17171)
    parser.add_argument("--path", default="/v1/ui/screenshot")
    parser.add_argument("--samples", type=int, default=30, help="number of measured requests")
    parser.add_argument("--warmup", type=int, default=3, help="warmup requests")
    parser.add_argument("--timeout", type=float, default=25.0)
    parser.add_argument("--interval-ms", type=int, default=0, help="sleep between measured requests")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--save-json", default="", help="save sample data to json file")
    parser.add_argument(
        "--keep-base64",
        action="store_true",
        help="keep full imageBase64 in saved json (will make file very large)",
    )
    args = parser.parse_args()

    if args.samples <= 0:
        print("samples must be > 0", file=sys.stderr)
        return 2
    if args.warmup < 0:
        print("warmup must be >= 0", file=sys.stderr)
        return 2

    url = f"http://{args.host}:{args.port}{args.path}"
    print("------------------------------------------------------------")
    print("ACTL Screenshot API Performance Test")
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
        result = request_once(url, args.timeout)
        result["index"] = i + 1
        samples.append(result)

        if args.verbose:
            j = result.get("json")
            code = None
            if isinstance(j, dict):
                code = j.get("code")
            print(
                f"[{i + 1:03d}] http={result['http_status']} code={code} "
                f"lat={result['latency_ms']:.2f}ms resp={result['resp_bytes']}B "
                f"ok={app_success(result)}"
            )

        if args.interval_ms > 0 and i != args.samples - 1:
            time.sleep(args.interval_ms / 1000.0)
    elapsed = time.perf_counter() - t0

    summarize(samples, url, elapsed)

    if args.save_json:
        saved_samples = []
        for s in samples:
            cloned = dict(s)
            j = cloned.get("json")
            if isinstance(j, dict):
                j2 = dict(j)
                data = j2.get("data")
                if isinstance(data, dict):
                    d2 = dict(data)
                    image_b64 = d2.get("imageBase64")
                    if isinstance(image_b64, str) and not args.keep_base64:
                        d2["imageBase64Head"] = image_b64[:24]
                        d2["imageBase64Length"] = len(image_b64)
                        d2["imageBase64"] = "<omitted>"
                    j2["data"] = d2
                cloned["json"] = j2
            saved_samples.append(cloned)

        payload = {
            "target": url,
            "warmup": args.warmup,
            "samples": args.samples,
            "timeout": args.timeout,
            "elapsed_seconds": elapsed,
            "data": saved_samples,
        }
        with open(args.save_json, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print(f"Saved raw results: {args.save_json}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
