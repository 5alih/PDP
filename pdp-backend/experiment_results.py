import requests
import statistics
import time
import csv

BASE_URL = "http://localhost:8088"

COMMON = {
    "numDistricts": 6,
    "year": 2023,
    "maxRecords": 5000,
    "gridSize": 10,
    "initialTemperature": 100.0,
    "coolingRate": 0.95,
    "iterationsPerTemp": 200,
    "maxIterations": 5000
}

def workload_stats(workloads):
    if not workloads:
        return 0, 0, 0, 0
    max_w = max(workloads)
    min_w = min(workloads)
    range_w = max_w - min_w
    std_w = statistics.pstdev(workloads)
    return min_w, max_w, range_w, std_w

def run_algorithm(algorithm, body):
    url = f"{BASE_URL}/api/{algorithm}/solve"
    response = requests.post(url, json=body, timeout=180)
    response.raise_for_status()
    data = response.json()

    workloads = data.get("workloads", [])
    min_w, max_w, range_w, std_w = workload_stats(workloads)

    return {
        "algorithm": algorithm.upper(),
        "objective": data.get("objectiveValue"),
        "feasible": data.get("feasible"),
        "runtime_seconds": data.get("runtimeMillis", 0) / 1000,
        "min_workload": min_w,
        "max_workload": max_w,
        "avg_workload": data.get("avgWorkload"),
        "workload_range": range_w,
        "workload_std": std_w,
        "workloads": workloads
    }

def experiment_runtime():
    rows = []
    time_limits = [5000, 10000, 30000, 60000]

    for t in time_limits:
        for alg in ["pdp", "sa"]:
            body = dict(COMMON)
            body["lambda"] = 0.5
            body["timeLimitMillis"] = t

            print(f"Running {alg.upper()} runtime experiment: {t/1000:.0f}s")
            result = run_algorithm(alg, body)
            result["experiment"] = "runtime"
            result["time_limit_seconds"] = t / 1000
            result["lambda"] = 0.5
            rows.append(result)

            time.sleep(1)

    return rows

def experiment_lambda():
    rows = []
    lambdas = [0.3, 0.5, 0.7, 0.9]

    for lam in lambdas:
        for alg in ["pdp", "sa"]:
            body = dict(COMMON)
            body["lambda"] = lam
            body["timeLimitMillis"] = 30000

            print(f"Running {alg.upper()} lambda experiment: lambda={lam}")
            result = run_algorithm(alg, body)
            result["experiment"] = "lambda"
            result["time_limit_seconds"] = 30
            result["lambda"] = lam
            rows.append(result)

            time.sleep(1)

    return rows

def save_csv(filename, rows):
    fields = [
        "experiment",
        "algorithm",
        "time_limit_seconds",
        "lambda",
        "objective",
        "feasible",
        "runtime_seconds",
        "min_workload",
        "max_workload",
        "avg_workload",
        "workload_range",
        "workload_std",
        "workloads"
    ]

    with open(filename, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field) for field in fields})

def print_table(title, rows):
    print("\n" + title)
    print("-" * len(title))
    print(f"{'Alg':<6} {'Time':<6} {'Lambda':<8} {'Obj':<10} {'MinW':<10} {'MaxW':<10} {'Range':<10} {'Std':<10} {'Run(s)':<10} {'Feasible'}")

    for r in rows:
        print(
            f"{r['algorithm']:<6} "
            f"{r['time_limit_seconds']:<6.0f} "
            f"{r['lambda']:<8.1f} "
            f"{r['objective']:<10.6f} "
            f"{r['min_workload']:<10.3f} "
            f"{r['max_workload']:<10.3f} "
            f"{r['workload_range']:<10.3f} "
            f"{r['workload_std']:<10.3f} "
            f"{r['runtime_seconds']:<10.2f} "
            f"{r['feasible']}"
        )

if __name__ == "__main__":
    runtime_rows = experiment_runtime()
    lambda_rows = experiment_lambda()

    all_rows = runtime_rows + lambda_rows

    save_csv("experiment_results.csv", all_rows)

    print_table("Experiment 1: Runtime Limit Sensitivity", runtime_rows)
    print_table("Experiment 2: Lambda Sensitivity", lambda_rows)

    print("\nSaved results to experiment_results.csv")