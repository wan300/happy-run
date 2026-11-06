"""Measure a running phone proxy through adb forward, without credentials or body logging.

Example: python tools/probe_capture_tls.py --adb-port 5047 --serial DEVICE --proxy-port 40000
The phone must already be running capture. This sends two unauthenticated GET / requests.
"""
import argparse
import http.client
import json
import socket
import ssl
import subprocess
import time


def read_head(connection):
    data = bytearray()
    while not data.endswith(b"\r\n\r\n"):
        part = connection.recv(1)
        if not part:
            raise EOFError("Proxy closed before CONNECT reply")
        data.extend(part)
        if len(data) > 65536:
            raise ValueError("Oversized CONNECT reply")
    return bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--proxy-port", type=int, required=True)
    args = parser.parse_args()
    base = [args.adb, "-P", str(args.adb_port), "-s", args.serial]
    local = subprocess.check_output(
        base + ["forward", "tcp:0", f"tcp:{args.proxy_port}"], text=True, timeout=10
    ).strip()
    result = {"connect_ms": None, "tls_ms": None, "requests": [], "reuse_ok": False}
    try:
        with socket.create_connection(("127.0.0.1", int(local)), timeout=10) as raw:
            started = time.perf_counter()
            raw.sendall(b"CONNECT api2.lptiyu.com:443 HTTP/1.1\r\nHost: api2.lptiyu.com:443\r\n\r\n")
            head = read_head(raw)
            if not head.startswith(b"HTTP/1.1 200 "):
                raise RuntimeError("CONNECT rejected")
            result["connect_ms"] = round((time.perf_counter() - started) * 1000, 1)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            # Intentional only for this loopback MITM probe; the proxy still validates its upstream.
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.maximum_version = ssl.TLSVersion.TLSv1_2
            started = time.perf_counter()
            with context.wrap_socket(raw, server_hostname="api2.lptiyu.com") as secure:
                result["tls_ms"] = round((time.perf_counter() - started) * 1000, 1)
                result["tls_version"] = secure.version()
                secure.settimeout(35)
                for index in range(2):
                    if index:
                        time.sleep(2)
                    started = time.perf_counter()
                    secure.sendall(b"GET / HTTP/1.1\r\nHost: api2.lptiyu.com\r\nUser-Agent: Lepao-Network-Probe/1.0\r\nConnection: keep-alive\r\n\r\n")
                    response = http.client.HTTPResponse(secure)
                    response.begin()
                    headers_ms = round((time.perf_counter() - started) * 1000, 1)
                    size = 0
                    while True:
                        chunk = response.read(16384)
                        if not chunk:
                            break
                        size += len(chunk)
                        if size > 8 * 1024 * 1024:
                            raise ValueError("Probe response exceeds 8 MB")
                    result["requests"].append({"status": response.status, "headers_ms": headers_ms,
                        "total_ms": round((time.perf_counter() - started) * 1000, 1), "bytes": size})
                    response.close()
                result["reuse_ok"] = True
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        subprocess.run(base + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result["reuse_ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
