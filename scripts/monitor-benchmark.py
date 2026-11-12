"""Optional OS sampler: python monitor-benchmark.py STATE_JSON [seconds]. Requires psutil.

Writes only PID/CPU/memory/thread counts to the ignored benchmark directory.
Create monitor.stop in that directory to stop before the maximum duration.
"""
import datetime
import json
import pathlib
import sys
import time

import psutil

state = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
directory = pathlib.Path(state["runDirectory"])
duration = int(sys.argv[2]) if len(sys.argv) > 2 else 300
processes = {}
for name, key in [("app", "appPid"), ("mysql", "databasePid")]:
    launcher = psutil.Process(state[key])
    # Windows Oracle Java and MySQL launchers spawn the actual workers.
    processes[name] = [launcher, *launcher.children(recursive=True)]
    for proc in processes[name]:
        proc.cpu_percent(None)
psutil.cpu_percent(None)
deadline = time.monotonic() + duration
with (directory / "host-monitor-v2.jsonl").open("a", encoding="utf-8") as stream:
    while time.monotonic() < deadline and not (directory / "monitor.stop").exists():
        time.sleep(1)
        row = {"schemaVersion": 2, "at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
               "systemCpuPercent": psutil.cpu_percent(None),
               "availableMemoryMb": psutil.virtual_memory().available / 1024**2,
               "logicalCpus": psutil.cpu_count(), "processes": {}}
        for name, group in processes.items():
            values = []
            for proc in group:
                try:
                    values.append({"pid": proc.pid, "cpuPercentOfOneCore": proc.cpu_percent(None),
                                   "rssMb": proc.memory_info().rss / 1024**2, "threads": proc.num_threads()})
                except psutil.NoSuchProcess:
                    pass
            row["processes"][name] = {"members": values,
                "cpuPercentOfOneCore": sum(p["cpuPercentOfOneCore"] for p in values),
                "rssMb": sum(p["rssMb"] for p in values)}
        stream.write(json.dumps(row) + "\n")
        stream.flush()
