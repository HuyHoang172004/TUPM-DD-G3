"""Rebuild a report from a persisted run; raw observations are never fabricated."""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lab.catalog import lookup
from lab.analysis import summarize
from lab.runner import Runner

parser = argparse.ArgumentParser()
parser.add_argument("run_id")
args = parser.parse_args()
runner = Runner()
runner.load(recover=False)
if args.run_id not in runner.runs:
    parser.error("Unknown run ID")
run = runner.runs[args.run_id]
method, _ = lookup(run["method_id"], run["case_id"])
if run["status"] not in ("completed", "failed", "incomplete", "cancelled"):
    parser.error("Cannot analyze a running experiment")
run["summary"] = summarize(run["trials"], method["metrics"])
runner.persist(run)
runner.report(run, method)
print(runner.raw / run["id"] / "report.html")
