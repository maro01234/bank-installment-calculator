"""Run against a started server: python3 tests/http_test.py."""

import concurrent.futures
import json
import os
import urllib.error
import urllib.parse
import urllib.request


BASE = os.environ.get("BASE_URL", "http://localhost:10000")


def request(path, form=None, method=None, content_type=None):
    body = None
    headers = {}
    if form is not None:
        body = urllib.parse.urlencode(form).encode("utf-8")
        headers["Content-Type"] = content_type or "application/x-www-form-urlencoded; charset=utf-8"
    elif content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(BASE + path, data=body, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def calculate(principal="1000000", rate="3", months="12", method="equal-payment"):
    status, headers, body = request("/api/calculate", {
        "principal": principal,
        "annualRate": rate,
        "months": months,
        "method": method,
        "firstPaymentMonth": "2026-10",
    })
    assert headers["Content-Type"].startswith("application/json")
    return status, json.loads(body)


assert request("/healthz")[2] == b"ok"
assert request("/")[0] == 200
assert request("/app.js")[1]["Content-Type"].startswith("text/javascript")
assert request("/style.css")[0] == 200
assert request("/favicon.svg")[0] == 200
assert request("/", method="HEAD")[2] == b""
assert request("/missing")[0] == 404
assert request("/../src/WebServer.java")[0] == 404
assert request("/api/calculate")[0] == 405

status, result = calculate()
assert status == 200
assert result["regularPayment"] == 84694
assert result["totalPayment"] == 1016325
assert result["totalInterest"] == 16325
assert len(result["schedule"]) == 12
assert result["schedule"][0] == {
    "number": 1,
    "month": "2026-10",
    "payment": 84694,
    "principal": 82194,
    "interest": 2500,
    "balance": 917806,
}
assert result["schedule"][-1]["balance"] == 0

status, result = calculate("120000", "12", "12", "equal-principal")
assert status == 200
assert result["firstPayment"] == 11200
assert result["lastPayment"] == 10100
assert result["totalInterest"] == 7800

for field, value in (("principal", "0"), ("annualRate", "101"), ("months", "601")):
    form = {
        "principal": "1000000",
        "annualRate": "3",
        "months": "12",
        "method": "equal-payment",
        "firstPaymentMonth": "2026-10",
    }
    form[field] = value
    status, _, body = request("/api/calculate", form)
    assert status == 400
    assert "error" in json.loads(body)

assert request(
    "/api/calculate",
    {"principal": "1000000"},
    content_type="application/json",
)[0] == 415


def concurrent_calculation(index):
    status, result = calculate(str(1_000_000 + index), "2.5", "36")
    assert status == 200
    assert result["schedule"][-1]["balance"] == 0


with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    list(pool.map(concurrent_calculation, range(16)))

print("HTTP tests passed (assets, validation, calculations, concurrency)")
