#!/usr/bin/env python3
# ============================================================================
# Оркестратор нагрузочного теста SCADA.
#
#   python3 run.py <profile> [--keep] [--build]
#
# Что делает для выбранного профиля (profiles.yaml):
#   1. генерит согласованную пару конфигов (gen_config.py) под размер/частоту;
#   2. поднимает стек (docker compose + override), ждёт готовности шлюза;
#   3. снимает метрики в CSV каждые metrics_interval_s:
#        • produce rate  — прирост суммарного offset'а топика scada.tags
#                          (= реально ли шлюз выдаёт ожидаемый tags/poll поток);
#        • JVM шлюза      — heap, CPU, потоки, GC (из /actuator/metrics);
#   4. для лестницы (stress) — повторяет по ступеням; для wave — пауза/возврат
#      симулятора; для soak — chaos по расписанию;
#   5. гасит стек (если не --keep).
#
# Метрики: loadtest/metrics/<profile>_<timestamp>.csv
# Стек локальный: sim → gateway → Kafka. Монитора здесь нет — меряем сторону
# производства (шлюз+Kafka). Consumer-lag монитора — отдельный тест с монитором.
# ============================================================================
import argparse
import csv
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent


def setup_docker_host():
    """Fedora: docker CLI ходит в podman-сокет через DOCKER_HOST (как в up.sh).
    Если DOCKER_HOST не задан, а podman-сокет есть — подставляем его."""
    if os.environ.get("DOCKER_HOST"):
        return
    sock = f"/run/user/{os.getuid()}/podman/podman.sock"
    if os.path.exists(sock):
        os.environ["DOCKER_HOST"] = f"unix://{sock}"
        print(f"DOCKER_HOST → {os.environ['DOCKER_HOST']} (podman)")
COMPOSE = ["docker", "compose",
           "-f", "docker-compose.yml",
           "-f", "loadtest/docker-compose.loadtest.yml"]
GATEWAY_ACTUATOR = "http://localhost:8888/actuator"
KAFKA_CONTAINER = "scada-kafka"
# Топик телеметрии = тот, что слушает монитор (kafka.topics.telemetry в шлюзе).
TELEMETRY_TOPIC = "scada.tags"


# ------------------------------------------------------------------ утилиты --
def sh(cmd, **kw):
    """Запуск команды в корне репозитория. Возвращает CompletedProcess."""
    return subprocess.run(cmd, cwd=REPO_ROOT, text=True,
                          capture_output=True, **kw)


def compose(*args, env_extra=None):
    """Запуск docker compose с обоими compose-файлами стенда; env_extra добавляется к окружению."""
    import os
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    return subprocess.run(COMPOSE + list(args), cwd=REPO_ROOT, text=True, env=env)


def gen_configs(tags, poll_ms, protocol, mix_opc, bool_ratio):
    """Вызвать генератор пары конфигов."""
    cmd = [sys.executable, str(HERE / "gen_config.py"),
           "--tags", str(tags), "--poll-ms", str(poll_ms),
           "--protocol", protocol, "--mix-opc", str(mix_opc),
           "--bool-ratio", str(bool_ratio)]
    r = subprocess.run(cmd, text=True, capture_output=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        print(r.stderr, file=sys.stderr)
        sys.exit(f"gen_config упал на tags={tags}")


# --------------------------------------------------------------- измерения --
def topic_total_offset():
    """Суммарный high-watermark топика телеметрии = всего произведено сообщений.
    Растёт монотонно даже при retention (offset не убывает). Разница по времени
    даёт produce rate. Возвращает int или None (топик/брокер недоступны)."""
    r = sh(["docker", "exec", KAFKA_CONTAINER,
            "/opt/kafka/bin/kafka-get-offsets.sh",
            "--bootstrap-server", "localhost:9092",
            "--topic", TELEMETRY_TOPIC])
    if r.returncode != 0 or not r.stdout.strip():
        return None
    total = 0
    for line in r.stdout.strip().splitlines():
        # формат: topic:partition:offset
        parts = line.split(":")
        if len(parts) == 3 and parts[2].strip().isdigit():
            total += int(parts[2])
    return total


def actuator(name, tag=None, stat="VALUE"):
    """Прочитать метрику actuator. Возвращает float или None."""
    url = f"{GATEWAY_ACTUATOR}/metrics/{name}"
    if tag:
        url += f"?tag={tag}"
    try:
        with urllib.request.urlopen(url, timeout=3) as resp:
            data = json.loads(resp.read())
        for m in data.get("measurements", []):
            if m.get("statistic") == stat:
                return m.get("value")
    except Exception:
        return None
    return None


def gateway_healthy():
    """True, если /actuator/health шлюза отвечает status=UP (иначе, в т.ч. при ошибке, False)."""
    try:
        with urllib.request.urlopen(f"{GATEWAY_ACTUATOR}/health", timeout=3) as resp:
            return json.loads(resp.read()).get("status") == "UP"
    except Exception:
        return False


def wait_gateway(timeout_s=420):
    """Ждём, пока шлюз поднимется (на больших конфигах старт = синк YAML→Postgres,
    может быть долгим). Возвращает True/False."""
    print(f"⏳ жду готовности шлюза (до {timeout_s}с)…", end="", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if gateway_healthy():
            print(f" ✅ за {time.time()-t0:.0f}с")
            return True
        print(".", end="", flush=True)
        time.sleep(5)
    print(" ❌ таймаут")
    return False


def snapshot():
    """Мгновенный срез метрик шлюза."""
    heap = actuator("jvm.memory.used", tag="area:heap")
    return {
        "heap_mb": round(heap / 1e6, 1) if heap is not None else None,
        "cpu_pct": round(actuator("process.cpu.usage") * 100, 1)
                   if actuator("process.cpu.usage") is not None else None,
        "threads": actuator("jvm.threads.live"),
        "gc_count": actuator("jvm.gc.pause", stat="COUNT"),
        "gc_time_s": actuator("jvm.gc.pause", stat="TOTAL_TIME"),
    }


# ------------------------------------------------------------ chaos / wave --
def docker(*args):
    """Тихий вызов docker (вывод проглатывается) — для pause/unpause/restart в chaos/wave."""
    subprocess.run(["docker", *args], cwd=REPO_ROOT,
                   capture_output=True, text=True)


# ----------------------------------------------------------- прогон ступени --
def run_rung(writer, csvfile, rung_label, tags, poll_ms, expected_rate,
             duration_s, interval_s, wave=None, chaos=None):
    """Снимает метрики в течение duration_s, обслуживая wave/chaos."""
    print(f"\n▶ ступень [{rung_label}]: tags={tags}, poll={poll_ms}ms, "
          f"ожидаемый поток ≈ {expected_rate:,.0f} msg/s, {duration_s}с")

    t0 = time.time()
    prev_total, prev_t = topic_total_offset(), t0
    pending = list(chaos or [])          # ещё не сработавшие chaos-события
    unpause_deadline = {}                 # что и когда вернуть после паузы
    wave_paused = False

    while time.time() - t0 < duration_s:
        time.sleep(interval_s)
        now = time.time()
        elapsed = now - t0
        note = ""

        # --- WAVE: периодически паузим/возвращаем симулятор ---
        if wave:
            period = wave["on_s"] + wave["off_s"]
            phase = elapsed % period
            should_pause = phase >= wave["on_s"]
            if should_pause and not wave_paused:
                docker("compose", "-f", "docker-compose.yml",
                       "-f", "loadtest/docker-compose.loadtest.yml", "pause", "simulator")
                wave_paused = True; note = "sim_pause"
            elif not should_pause and wave_paused:
                docker("compose", "-f", "docker-compose.yml",
                       "-f", "loadtest/docker-compose.loadtest.yml", "unpause", "simulator")
                wave_paused = False; note = "sim_unpause"

        # --- CHAOS: события по расписанию ---
        for ev in list(pending):
            if elapsed >= ev["at_s"]:
                act = ev["action"]
                if act == "kafka_pause":
                    docker("compose", "-f", "docker-compose.yml",
                           "-f", "loadtest/docker-compose.loadtest.yml", "pause", "kafka")
                    unpause_deadline["kafka"] = elapsed + ev.get("secs", 30)
                    note = f"chaos:kafka_pause({ev.get('secs',30)}s)"
                elif act == "sim_restart":
                    docker("compose", "-f", "docker-compose.yml",
                           "-f", "loadtest/docker-compose.loadtest.yml",
                           "restart", "simulator")
                    note = "chaos:sim_restart"
                pending.remove(ev)
        for svc, deadline in list(unpause_deadline.items()):
            if elapsed >= deadline:
                docker("compose", "-f", "docker-compose.yml",
                       "-f", "loadtest/docker-compose.loadtest.yml", "unpause", svc)
                note = f"chaos:{svc}_unpause"
                del unpause_deadline[svc]

        # --- измерения ---
        total = topic_total_offset()
        rate = None
        if total is not None and prev_total is not None and now > prev_t:
            rate = (total - prev_total) / (now - prev_t)
        snap = snapshot()

        row = {
            "t_s": round(elapsed, 1),
            "rung": rung_label,
            "tags": tags,
            "poll_ms": poll_ms,
            "expected_rate": round(expected_rate),
            "produced_total": total if total is not None else "",
            "produced_rate": round(rate) if rate is not None else "",
            "achieved_pct": round(100 * rate / expected_rate) if rate and expected_rate else "",
            **snap,
            "note": note,
        }
        writer.writerow(row)
        csvfile.flush()

        rate_s = f"{rate:>7,.0f}" if rate is not None else "   n/a "
        pct = f"{row['achieved_pct']:>3}%" if row["achieved_pct"] != "" else " n/a"
        heap_s = f"{snap['heap_mb']}MB" if snap["heap_mb"] is not None else "n/a"
        cpu_s = f"{snap['cpu_pct']}%" if snap["cpu_pct"] is not None else "n/a"
        print(f"  t={elapsed:5.0f}s  rate={rate_s}/s ({pct} от плана)  "
              f"heap={heap_s}  cpu={cpu_s}  {note}")

        if total is not None:
            prev_total, prev_t = total, now

    # вернуть симулятор, если остался на паузе после wave
    if wave and wave_paused:
        docker("compose", "-f", "docker-compose.yml",
               "-f", "loadtest/docker-compose.loadtest.yml", "unpause", "simulator")


# ------------------------------------------------------------------- main ---
def main():
    """Прогон профиля из profiles.yaml: генерит конфиги, поднимает стек, снимает метрики
    по ступеням в CSV (обслуживая wave/chaos), гасит стек (если не --keep) и печатает сводку."""
    ap = argparse.ArgumentParser(description="Оркестратор нагрузочного теста SCADA")
    ap.add_argument("profile", help="имя профиля из profiles.yaml")
    ap.add_argument("--keep", action="store_true", help="не гасить стек после теста")
    ap.add_argument("--build", action="store_true", help="пересобрать образы перед стартом")
    ap.add_argument("--min-rate", type=float, default=None,
                    help="CI-гейт: упасть (exit 1), если достигнутый throughput любой "
                         "ступени ниже этого значения (msg/s)")
    args = ap.parse_args()

    # Построчная буферизация stdout: иначе python-принты копятся в буфере и в логе
    # перемешиваются с прямым выводом docker compose (сбивает порядок событий).
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:
        pass

    setup_docker_host()

    cfg = yaml.safe_load((HERE / "profiles.yaml").read_text())
    defaults = cfg.get("defaults", {})
    if args.profile not in cfg["profiles"]:
        sys.exit(f"нет профиля '{args.profile}'. Есть: {', '.join(cfg['profiles'])}")
    p = {**defaults, **cfg["profiles"][args.profile]}

    protocol = p.get("protocol", "opcua")
    mix_opc = p.get("mix_opc", 0.5)
    bool_ratio = p.get("bool_ratio", 0.3)
    poll_ms = p.get("poll_ms", 1000)
    interval_s = p.get("metrics_interval_s", 5)
    persist = "true" if p.get("persist_telemetry") else "false"

    # ступени: лестница или одиночный прогон
    if "ladder" in p:
        rungs = [(t, p.get("step_duration_s", 180)) for t in p["ladder"]]
    else:
        rungs = [(p["tags"], p["duration_s"])]

    # CSV
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = HERE / "metrics" / f"{args.profile}_{stamp}.csv"
    fields = ["t_s", "rung", "tags", "poll_ms", "expected_rate", "produced_total",
              "produced_rate", "achieved_pct", "heap_mb", "cpu_pct", "threads",
              "gc_count", "gc_time_s", "note"]

    print(f"=== нагрузочный тест: профиль '{args.profile}' ===")
    print(f"протокол={protocol}, poll={poll_ms}ms, ступени={[r[0] for r in rungs]}, "
          f"persist_telemetry={persist}")
    print(f"метрики → {csv_path}")

    env_extra = {"LT_PERSIST_TELEMETRY": persist}
    first = True

    with open(csv_path, "w", newline="") as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=fields)
        writer.writeheader()

        try:
            for tags, duration in rungs:
                gen_configs(tags, poll_ms, protocol, mix_opc, bool_ratio)
                eff_poll = max(poll_ms, 100)
                expected_rate = tags / (eff_poll / 1000.0)

                if first:
                    up = ["up", "-d"] + (["--build"] if args.build else [])
                    compose(*up, env_extra=env_extra)
                    first = False
                else:
                    # новая ступень: перезапустить только sim+gateway с новым конфигом
                    compose("up", "-d", "--force-recreate", "--no-deps",
                            "simulator", "gateway", env_extra=env_extra)

                if not wait_gateway():
                    print("шлюз не поднялся — снимаю логи и перехожу дальше")
                    sh(["docker", "logs", "--tail", "40", "scada-gateway"])
                    continue

                run_rung(writer, csvfile,
                         rung_label=f"{tags}@{poll_ms}ms",
                         tags=tags, poll_ms=poll_ms, expected_rate=expected_rate,
                         duration_s=duration, interval_s=interval_s,
                         wave=p.get("wave"), chaos=p.get("chaos"))
        except KeyboardInterrupt:
            print("\n⏹ прервано пользователем")
        finally:
            if not args.keep:
                print("🧹 гашу стек…")
                compose("down")
            else:
                print("стек оставлен (--keep). Погасить: "
                      "docker compose -f docker-compose.yml -f loadtest/docker-compose.loadtest.yml down")

    print(f"\n✅ готово. Метрики: {csv_path}")
    summarize(csv_path)

    # CI-гейт: если задан порог — проверяем достигнутый throughput и роняем
    # сборку (exit≠0) при недоборе. Без --min-rate тест остаётся справочным.
    if args.min_rate is not None and not check_min_rate(csv_path, args.min_rate):
        sys.exit(f"❌ нагрузочный гейт НЕ пройден (порог {args.min_rate:g} msg/s)")


def _stable_peak(rates):
    """Устойчивый «достигнутый» rate ступени: медиана ВЕРХНЕЙ половины замеров
    (отсекает разгон в начале и разовые просадки). Пустой список → 0.0."""
    import statistics
    return statistics.median(sorted(rates)[len(rates) // 2:]) if rates else 0.0


def _rung_peaks(rows):
    """{ступень: устойчивый достигнутый rate} по строкам CSV (пустые rate пропускаются)."""
    by_rung = {}
    for r in rows:
        by_rung.setdefault(r["rung"], []).append(r)
    return {rung: _stable_peak([float(x["produced_rate"]) for x in rs
                                if x["produced_rate"] not in ("", None)])
            for rung, rs in by_rung.items()}


def summarize(csv_path):
    """Короткая сводка по ступеням: устойчивый produce rate и пик heap."""
    rows = list(csv.DictReader(open(csv_path)))
    if not rows:
        return
    by_rung = {}
    for r in rows:
        by_rung.setdefault(r["rung"], []).append(r)
    print("\n── сводка по ступеням ──")
    print(f"{'ступень':>16}  {'план msg/s':>11}  {'достигнуто':>11}  {'% плана':>8}  {'пик heap':>9}")
    for rung, rs in by_rung.items():
        rates = [float(x["produced_rate"]) for x in rs if x["produced_rate"] not in ("", None)]
        heaps = [float(x["heap_mb"]) for x in rs if x["heap_mb"] not in ("", None)]
        exp = next((float(x["expected_rate"]) for x in rs if x["expected_rate"] not in ("", None)), 0)
        peak = _stable_peak(rates)
        pct = round(100 * peak / exp) if exp else 0
        print(f"{rung:>16}  {exp:>11,.0f}  {peak:>11,.0f}  {pct:>7}%  "
              f"{max(heaps) if heaps else 0:>7.0f}MB")


def check_min_rate(csv_path, min_rate):
    """CI-гейт: True, если КАЖДАЯ ступень достигла ≥ min_rate msg/s.
    Пустой CSV (стек не поднялся / шлюз не ответил) — тоже провал."""
    peaks = _rung_peaks(list(csv.DictReader(open(csv_path))))
    print(f"\n── гейт: порог ≥ {min_rate:,.0f} msg/s ──")
    if not peaks:
        print("  ❌ нет данных о throughput (стек не поднялся?)")
        return False
    ok = True
    for rung, peak in peaks.items():
        passed = peak >= min_rate
        ok = ok and passed
        print(f"  {'✅' if passed else '❌'} {rung}: {peak:,.0f} msg/s")
    return ok


if __name__ == "__main__":
    main()
