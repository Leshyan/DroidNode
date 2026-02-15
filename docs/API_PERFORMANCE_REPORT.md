# DroidNode API Performance Report

- Generated: 2026-02-13 19:25:51
- Target: `http://192.168.0.105:17175`
- Config: warmup=2, samples=20, intervalMs=0, timeout=30.0s
- Total wall time: 86.797s

## Endpoint Summary

| API | Method | Success | Mean (ms) | P50 | P90 | P95 | P99 | Resp Avg (B) | Image Avg (B) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `/v1/health` | GET | 100.00% (20/20) | 48.35 | 45.58 | 61.85 | 65.26 | 65.78 | 48.0 | 0.0 |
| `/v1/system/info` | GET | 100.00% (20/20) | 113.95 | 114.42 | 124.71 | 125.19 | 128.21 | 686.0 | 0.0 |
| `/v1/control/click` | POST | 100.00% (20/20) | 101.06 | 90.07 | 136.34 | 185.11 | 192.33 | 64.0 | 0.0 |
| `/v1/control/swipe` | POST | 100.00% (20/20) | 211.42 | 212.70 | 222.70 | 224.06 | 230.42 | 96.0 | 0.0 |
| `/v1/control/input` | POST | 100.00% (20/20) | 807.88 | 798.86 | 882.51 | 908.69 | 1142.36 | 132.0 | 0.0 |
| `/v1/ui/xml` | POST | 100.00% (20/20) | 2234.79 | 2243.08 | 2271.18 | 2272.72 | 2285.65 | 11364.0 | 11364.0 |
| `/v1/ui/screenshot` | POST | 100.00% (20/20) | 414.51 | 407.83 | 443.33 | 450.69 | 529.39 | 130731.0 | 130731.0 |

## Bottlenecks (by P95 latency)

1. `/v1/ui/xml` (POST): P95=2272.72ms, P99=2285.65ms, success=100.00%
2. `/v1/control/input` (POST): P95=908.69ms, P99=1142.36ms, success=100.00%
3. `/v1/ui/screenshot` (POST): P95=450.69ms, P99=529.39ms, success=100.00%

## Reliability

- All tested APIs reached 100% success in this run.

## Reproduce

```bash
python3 tests/api_perf_tester.py 192.168.0.105 17175 --warmup 2 --samples 20 --interval-ms 0 --timeout 30.0
```
