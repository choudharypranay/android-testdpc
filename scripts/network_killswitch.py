#!/usr/bin/env python3
"""Remote control for TestDPC's network kill switch.

The interesting part is how success is confirmed. Cutting the network destroys the
channel the request was made over, so the reply cannot say whether the cut worked.
Instead the phone answers 200 immediately, waits a second, and only then pulls the
network down. This client waits five seconds and probes ``GET /ping``:

    unreachable -> the kill switch worked
    still answering -> it did not

Examples:

    # block the network for 30 minutes
    python network_killswitch.py --host 192.168.1.42 --token abc123 off --minutes 30

    # block until released by hand from inside the app
    python network_killswitch.py --host 192.168.1.42 --token abc123 off

    # read the current state
    python network_killswitch.py --host 192.168.1.42 --token abc123 status
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

# The phone waits 1s after answering before it cuts the network; 5s leaves margin.
VERIFY_DELAY_SECONDS = 5
PROBE_TIMEOUT_SECONDS = 4
REQUEST_TIMEOUT_SECONDS = 10


def build_url(host, port, path):
    return "http://{}:{}{}".format(host, port, path)


def call(host, port, path, token, method="GET", timeout=REQUEST_TIMEOUT_SECONDS):
    """Returns (status_code, parsed_body). Raises URLError when unreachable."""
    request = urllib.request.Request(build_url(host, port, path), method=method)
    if token:
        request.add_header("X-Auth-Token", token)
    if method == "POST":
        request.data = b""
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw


def is_reachable(host, port):
    try:
        request = urllib.request.Request(build_url(host, port, "/ping"))
        with urllib.request.urlopen(request, timeout=PROBE_TIMEOUT_SECONDS) as response:
            return response.status == 200
    except Exception:
        return False


def cmd_off(args):
    path = "/network/off"
    if args.minutes is not None:
        path += "?minutes={}".format(args.minutes)

    if not is_reachable(args.host, args.port):
        print("FAIL: the phone is not reachable at {}:{} to begin with".format(args.host, args.port))
        return 2

    try:
        status, body = call(args.host, args.port, path, args.token, method="POST")
    except Exception as error:
        print("FAIL: the request itself failed: {}".format(error))
        return 2

    if status != 200:
        print("FAIL: the phone answered {}: {}".format(status, body))
        return 2
    print("Phone accepted the request: {}".format(json.dumps(body)))

    print("Waiting {}s, then probing to see whether the network really went down..."
          .format(VERIFY_DELAY_SECONDS))
    time.sleep(VERIFY_DELAY_SECONDS)

    if is_reachable(args.host, args.port):
        print("FAIL: {}:{} still answers, so the network was NOT cut".format(args.host, args.port))
        return 1

    if args.minutes:
        print("OK: the phone is unreachable, so the network is down for {} minutes"
              .format(args.minutes))
    else:
        print("OK: the phone is unreachable, so the network is down until released from the app")
    return 0


def cmd_on(args):
    try:
        status, body = call(args.host, args.port, "/network/on", args.token, method="POST")
    except Exception as error:
        print("FAIL: could not reach the phone: {}".format(error))
        print("      While the network is blocked the phone is off the LAN by design.")
        print("      Restore it from inside TestDPC, or over USB with:")
        print("        adb forward tcp:{p} tcp:{p} && "
              "python {s} --host 127.0.0.1 --port {p} --token TOKEN on"
              .format(p=args.port, s=sys.argv[0]))
        return 2
    if status != 200:
        print("FAIL: the phone answered {}: {}".format(status, body))
        return 2
    print("OK: {}".format(json.dumps(body)))
    return 0


def cmd_status(args):
    try:
        status, body = call(args.host, args.port, "/status", args.token)
    except Exception as error:
        print("FAIL: could not reach the phone: {}".format(error))
        return 2
    if status != 200:
        print("FAIL: the phone answered {}: {}".format(status, body))
        return 2
    print(json.dumps(body, indent=2))
    return 0


def cmd_reset_screentime(args):
    try:
        status, body = call(
            args.host, args.port, "/screentime/reset", args.token, method="POST")
    except Exception as error:
        print("FAIL: could not reach the phone: {}".format(error))
        return 2
    if status != 200:
        print("FAIL: the phone answered {}: {}".format(status, body))
        return 2
    print("OK: {}".format(json.dumps(body)))
    return 0


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", required=True, help="phone's IP address, or 127.0.0.1 with adb forward")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--token", default="", help="token shown on the Network kill switch screen")

    sub = parser.add_subparsers(dest="command", required=True)

    off = sub.add_parser("off", help="cut the network, then verify it really went down")
    off.add_argument("--minutes", type=int, default=None,
                     help="restore automatically after this many minutes (default: never)")
    off.set_defaults(func=cmd_off)

    on = sub.add_parser("on", help="restore the network (only works while still reachable)")
    on.set_defaults(func=cmd_on)

    status = sub.add_parser("status", help="print the current screen time and network state")
    status.set_defaults(func=cmd_status)

    reset = sub.add_parser("reset-screentime", help="give back a full screen time allowance")
    reset.set_defaults(func=cmd_reset_screentime)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
