#!/usr/bin/env python3
"""Generate byte-exact protocol golden fixtures for the Android JVM tests.

The fixtures are produced by the PC reference implementation (`lepao_client.protocol`,
already verified against the 2026-11-02 phone capture) and are consumed by
`android/app/src/test/java/com/lepao/companion/qa/ProtocolGoldenTest.kt`.

All material here is synthetic. The identity, UMID, tokens, measurements and object keys
are fixed placeholders chosen by `android/docs/verification.md`; nothing is copied from a
real account or a real capture.

Usage (from the repository root):

    D:/conda/python.exe android/tools/gen_fixtures.py
"""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path
from urllib.parse import parse_qsl

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
# 独立副本布局：<root>/android 与 <root>/pc 并列时，参考实现位于 pc/。
if not (REPO_ROOT / "lepao_client").is_dir():
    for _candidate in (REPO_ROOT / "pc", REPO_ROOT.parent / "pc"):
        if (_candidate / "lepao_client").is_dir():
            sys.path.insert(0, str(_candidate))
            break

from lepao_client import protocol  # noqa: E402

OUTPUT = REPO_ROOT / "android/app/src/test/resources/fixtures/protocol_golden.json"

# --- Fixed synthetic inputs (android/docs/verification.md section 2) -------------------------

IDENTITY = {
    "version": "231",
    "version_name": "4.3.1",
    "mobileModel": "ADA-AL00U",
    "mobileDeviceId": "fixture-device-0001",
    "mobileOsVersion": "12",
    "ostype": "1",
    "student_num": "23301135",
    "school_id": "12",
    "uid": "12217924",
    "token": "A" * 32,
}
UMID = "fixture-umid-0123456789abcdef"
TIMESTAMP = 1793778542
NONCE = "123456"
FORMAL_START_STR = "e" * 32
SCHOOL_ID = IDENTITY["school_id"]
UID = IDENTITY["uid"]

POINT_IDS = ["24560", "2894"]

# The 38-field stop request: 10 common + timestamp + nonce + 24 business fields + dynamic field.
DEVICE_FIELDS = {
    "oaid": "fixture-oaid-0001",
    "mac": "02:00:00:00:01:01",
}
KEYS = {
    "screenshot": "Public/Upload/file/run_image/2026-11-04/461/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
    "route": "Public/Upload/file/run_route/2026-11-04/461/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt",
}
LOG_DATA = [
    {"distance": 640.0, "latitude": 39.9518, "longtitude": 116.3452, "point_id": 24560, "time": 1793778300},
    {"distance": 1100.0, "latitude": 39.9528, "longtitude": 116.3462, "point_id": 2894, "time": 1793778420},
]
STEP_INFO = {"interval": 60, "list": [120, 130, 140, 150, 0]}
STOP_EXTRA = {
    **DEVICE_FIELDS,
    "term_id": "2026",
    "game_id": "158",
    "start_time": "1793778242",
    "end_time": str(TIMESTAMP),
    "used_time": "300",
    "distance": "2.03",
    "step_num": "1690",
    "file_img": KEYS["screenshot"],
    "record_file": KEYS["route"],
    "record_img": "",
    "log_data": json.dumps(LOG_DATA, ensure_ascii=False, separators=(",", ":")),
    "step_info": json.dumps(STEP_INFO, ensure_ascii=False, separators=(",", ":")),
    "run_check_res": json.dumps([], ensure_ascii=False, separators=(",", ":")),
    "pass_total": 2,
    "pass_effective_num": 2,
    "is_running_area_valid": "1",
    "ip": "203.0.113.7",
    "record_str": protocol.record_signature(SCHOOL_ID, UID, FORMAL_START_STR),
    "gee_token": "",
    "newMobileDeviceId": "",
    "run_face_verify_id": "1231231231231",
    "is_timeout_zfb": 0,
}

# Endpoint order mirrors the run flow: prepare -> start -> location -> OSS -> stop -> sensor
# -> verify -> token refresh.
REQUESTS = [
    {
        "name": "beforeRun",
        "endpoint": "Run2/beforeRunV260",
        "extra": {},
        "time_str": None,
    },
    {
        "name": "start",
        "endpoint": "Run/getTimestampV278",
        "extra": {"game_id": "158", "point_ids": ",".join(POINT_IDS)},
        "time_str": None,
    },
    {
        "name": "location",
        "endpoint": "Run/setRunLocationRecord",
        "extra": {
            "latitude": "39.9518",
            "longitude": "116.3452",
            "game_id": "158",
            "point_ids": ",".join(POINT_IDS),
        },
        "time_str": None,
    },
    {
        "name": "ossSts",
        "endpoint": "report/getOssSts",
        "extra": {},
        "time_str": None,
    },
    {
        "name": "stop",
        "endpoint": "Run/stopRunV278",
        "extra": STOP_EXTRA,
        "time_str": FORMAL_START_STR,
    },
    {
        "name": "gyroscope",
        "endpoint": "Run2/gyroscope",
        "extra": {
            "record_id": "259921",
            "gyroscope_file": "Public/Upload/file/run_gyroscope/2026-11-04/461/x.txt",
        },
        "time_str": None,
    },
    {
        "name": "recordDetail",
        "endpoint": "Run/recordDetailV270",
        "extra": {"record_id": "259921"},
        "time_str": None,
    },
    {
        "name": "refreshToken",
        "endpoint": "Login/RefreshToken",
        "extra": {"access_token": "A" * 32, "refresh_token": "R" * 32},
        "time_str": None,
    },
]


def encode_request(case: dict) -> dict:
    endpoint = case["endpoint"]
    # The reference builder reads `umid` from the identity mapping.
    request = protocol.build_request(
        endpoint,
        {**IDENTITY, "umid": UMID},
        case["extra"],
        TIMESTAMP,
        nonce=NONCE,
        time_str=case["time_str"],
    )
    body_text = request.body.decode("ascii")
    form = dict(parse_qsl(body_text, keep_blank_values=True))
    encrypted = protocol.CONTRACTS[endpoint].encrypted
    record = {
        "name": case["name"],
        "endpoint": endpoint,
        "frames": ["fixture/" + case["name"]],
        "extra": case["extra"],
        "extra_order": list(case["extra"].keys()),
        "time_str": case["time_str"],
        "field_order": list(request.fields.keys()),
        "fields": request.fields,
        "body": body_text,
        "body_b64": base64.b64encode(request.body).decode("ascii"),
        "form": form,
        "encrypted": encrypted,
    }
    if encrypted:
        # The reference encoder only ever emits `key=`; decrypting it yields the exact
        # plaintext the other side must reproduce (including the escaped forward slashes).
        record["encrypted_plaintext"] = protocol.decrypt_text(form["key"], "PARAMS_NEW_GAME")
    return record


def response_fixtures() -> dict:
    payload = {
        "record_id": "259921",
        "uid": UID,
        "start_time": "1793778242",
        "game_id": "158",
        "distance": "2.03",
        "time": "300",
        "step_num": "1690",
        "record_status": "1",
        "file": (
            "https://lptiyu-data.oss-cn-hangzhou.aliyuncs.com/"
            "Public/Upload/file/run_route/2026-11-04/461/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.txt"
        ),
    }
    payload_text = protocol.compact_json(payload)
    encrypted_data = protocol.encrypt_text(payload_text)
    return {
        "payload": payload,
        "payload_compact": payload_text,
        "plain": {"status": 1, "msg": "ok", "data": payload},
        "encrypted": {"status": 1, "msg": "ok", "data": encrypted_data},
        "encrypted_data_plaintext": payload_text,
        "failure": {"status": 0, "msg": "fixture business failure", "data": {"record_id": "259921"}},
        "failure_string_status": {"status": "0", "msg": "fixture string status"},
        "failure_missing_status": {"msg": "fixture envelope without status", "data": None},
        "undecryptable": {"status": 1, "msg": "ok", "data": "not-a-valid-ciphertext"},
    }


def b64_mime_vectors() -> list[dict]:
    text_inputs = ["", "a", "ab", "abc", "x" * 54, "x" * 55, "x" * 56, "x" * 57, "x" * 100]
    binary_inputs = [bytes([0x00]), bytes(range(16)), bytes(range(255, 0, -1)) + b"\x00\xff"]
    vectors = []
    for index, text in enumerate(text_inputs):
        vectors.append(
            {
                "name": f"text-{index}",
                "input_utf8": text,
                "expected": protocol.base64.encodebytes(text.encode()).decode("ascii"),
            }
        )
    for index, raw in enumerate(binary_inputs):
        vectors.append(
            {
                "name": f"binary-{index}",
                "input_hex": raw.hex(),
                "expected": protocol.base64.encodebytes(raw).decode("ascii"),
            }
        )
    return vectors


def vector_fixtures() -> dict:
    record_signatures = [
        {
            "school_id": SCHOOL_ID,
            "uid": UID,
            "time_str": FORMAL_START_STR,
            "expected": protocol.record_signature(SCHOOL_ID, UID, FORMAL_START_STR),
        },
        {
            "school_id": SCHOOL_ID,
            "uid": "12217925",
            "time_str": "0" * 32,
            "expected": protocol.record_signature(SCHOOL_ID, "12217925", "0" * 32),
        },
        {
            "school_id": "7",
            "uid": "1234567890",
            "time_str": "f" * 32,
            "expected": protocol.record_signature("7", "1234567890", "f" * 32),
        },
    ]
    record_signature_errors = [
        {
            "school_id": SCHOOL_ID,
            # 2**32 - 1 narrows to -1: outside the verified Java remainder domain.
            "uid": str(2**32 - 1),
            "time_str": FORMAL_START_STR,
            "reason": "negative-signed-uid",
        }
    ]
    dynamic_names = [
        {"model": "ADA-AL00U", "timestamp": str(TIMESTAMP), "expected": protocol.dynamic_name("ADA-AL00U", str(TIMESTAMP))},
        {"model": "iPhone 12", "timestamp": "1000000000", "expected": protocol.dynamic_name("iPhone 12", "1000000000")},
        {"model": "Mi 10", "timestamp": "1234567890", "expected": protocol.dynamic_name("Mi 10", "1234567890")},
    ]
    dynamic_name_errors = [
        {"model": "A", "timestamp": str(TIMESTAMP), "reason": "model-too-short"},
        {"model": "ADA-AL00U", "timestamp": "12345", "reason": "timestamp-not-10-digits"},
        {"model": "ADA-AL00U", "timestamp": "17937785a2", "reason": "timestamp-not-numeric"},
    ]
    dynamic_values = [
        {"timestamp": str(TIMESTAMP), "umid": UMID, "expected": protocol.dynamic_value(str(TIMESTAMP), UMID)},
        {"timestamp": "1234567890", "umid": UMID, "expected": protocol.dynamic_value("1234567890", UMID)},
        {"timestamp": "1000000000", "umid": "fixture-umid-ffffffffffffffff", "expected": protocol.dynamic_value("1000000000", "fixture-umid-ffffffffffffffff")},
    ]
    form_encode = [
        {"name": "space", "input": [["q", "a b"]], "expected": "q=a+b"},
        {"name": "reserved", "input": [["q", "a+b/c=d&e"]], "expected": "q=a%2Bb%2Fc%3Dd%26e"},
        {"name": "unreserved", "input": [["q", "AZaz09-_.~"]], "expected": "q=AZaz09-_.~"},
        {"name": "newline", "input": [["q", "line1\nline2"]], "expected": "q=line1%0Aline2"},
        {"name": "chinese", "input": [["cn", "中文 测试"]], "expected": "cn=%E4%B8%AD%E6%96%87+%E6%B5%8B%E8%AF%95"},
        {"name": "percent", "input": [["%", "100%"]], "expected": "%25=100%25"},
        {"name": "multiple", "input": [["a", "1"], ["b", "2"]], "expected": "a=1&b=2"},
        {"name": "blank", "input": [["k", ""]], "expected": "k="},
    ]
    json_quote = [
        {"input": "plain", "expected": protocol.compact_json("plain")},
        {"input": "slash/x", "expected": protocol.compact_json("slash/x")},
        {"input": 'a"b', "expected": protocol.compact_json('a"b')},
        {"input": "a\\b", "expected": protocol.compact_json("a\\b")},
        {"input": "line\nbreak", "expected": protocol.compact_json("line\nbreak")},
        {"input": "tab\there", "expected": protocol.compact_json("tab\there")},
        {"input": "ctrl\u0001", "expected": protocol.compact_json("ctrl\u0001")},
        {"input": "中文", "expected": protocol.compact_json("中文")},
        {"input": "", "expected": protocol.compact_json("")},
    ]
    md5 = [
        {"input": "abc", "expected": protocol.hashlib.md5(b"abc").hexdigest()},
        {"input": "", "expected": protocol.hashlib.md5(b"").hexdigest()},
        {"input": "中文", "expected": protocol.hashlib.md5("中文".encode()).hexdigest()},
    ]
    return {
        "record_signature": record_signatures,
        "record_signature_errors": record_signature_errors,
        "dynamic_name": dynamic_names,
        "dynamic_name_errors": dynamic_name_errors,
        "dynamic_value": dynamic_values,
        "b64_mime": b64_mime_vectors(),
        "form_encode": form_encode,
        "json_quote": json_quote,
        "md5": md5,
    }


def main() -> None:
    fixture = {
        "meta": {
            "generated_by": "android/tools/gen_fixtures.py",
            "reference": "lepao_client/protocol.py",
            "note": "Synthetic fixture data only; no real account, token, UMID or capture bytes.",
            "identity": IDENTITY,
            "umid": UMID,
            "timestamp": TIMESTAMP,
            "nonce": NONCE,
            "formal_start_str": FORMAL_START_STR,
        },
        "requests": [encode_request(case) for case in REQUESTS],
        "responses": response_fixtures(),
        "vectors": vector_fixtures(),
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")
    for record in fixture["requests"]:
        print(
            f"  {record['endpoint']:<28} fields={len(record['fields']):3d} "
            f"body={len(record['body']):4d}B encrypted={record['encrypted']}"
        )


if __name__ == "__main__":
    main()
