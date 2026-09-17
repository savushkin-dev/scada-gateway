"""
Тесты превращения серии архива в значение канала (core/archive_replay.py).

Серия синтетическая, собирается в памяти: 0..100 с. Точки (t, v):
  0 → 10, 20 → 3276.7 (код обрыва датчика), 40 → 30, 70 → 0.
Архив длится 100 с и зацикливается.
"""
import numpy as np
import pytest

from core.archive_replay import ArchiveReplay, ReplaySpec


@pytest.fixture
def replay():
    r = ArchiveReplay(data_path="unused.pkl.gz", loop=True)
    r.duration = 100.0
    r.series["S"] = (np.array([0.0, 20.0, 40.0, 70.0]), np.array([10.0, 3276.7, 30.0, 0.0]))
    return r


def spec(**kw):
    return ReplaySpec(source="S", **kw)


def test_value_is_zero_order_hold(replay):
    assert replay.evaluate(spec(), 5) == 10.0
    assert replay.evaluate(spec(), 45) == 30.0


def test_valid_range_holds_last_good_value_instead_of_sensor_fault(replay):
    # В 25 с сырое значение — код обрыва; канал держит предыдущее нормальное (10).
    assert replay.evaluate(spec(), 25) == 3276.7
    assert replay.evaluate(spec(valid_min=0, valid_max=120), 25) == 10.0


def test_threshold_gives_on_off(replay):
    s = spec(valid_max=120, above=5, on=75, off=0)
    assert replay.evaluate(s, 45) == 75
    assert replay.evaluate(s, 80) == 0


def test_scale_bias_and_clamp(replay):
    assert replay.evaluate(spec(valid_max=120, scale=0.5, bias=1), 45) == 16.0
    assert replay.evaluate(spec(valid_max=120, scale=10, clamp_max=100), 45) == 100


def test_negative_offset_of_closed_valve_is_clamped_to_zero(replay):
    replay.series["V"] = (np.array([0.0]), np.array([-2.31]))
    assert replay.evaluate(ReplaySpec(source="V", clamp_min=0, clamp_max=100), 10) == 0


def test_integral_accumulates_and_never_resets_on_loop(replay):
    s = spec(mode="integral", valid_max=120)
    # Отфильтрованная серия: 0→10, 40→30, 70→0. За круг: 10*40 + 30*30 + 0*30 = 1300.
    assert replay.evaluate(s, 40) == pytest.approx(400)
    assert replay.evaluate(s, 100) == pytest.approx(1300)
    assert replay.evaluate(s, 140) == pytest.approx(1700)
    values = [replay.evaluate(s, x) for x in range(0, 300, 7)]
    assert all(b >= a for a, b in zip(values, values[1:]))


def test_integral_of_condition_counts_active_seconds(replay):
    s = spec(mode="integral", valid_max=120, above=20, on=1, off=0)
    assert replay.evaluate(s, 100) == pytest.approx(30)


def test_age_is_seconds_since_last_change(replay):
    assert replay.evaluate(spec(mode="age"), 55) == pytest.approx(15)
    # До первой точки круга возраст считается с последней точки предыдущего круга.
    replay.series["late"] = (np.array([10.0, 60.0]), np.array([1.0, 2.0]))
    assert replay.evaluate(ReplaySpec(source="late", mode="age"), 5) == pytest.approx(45)


def test_age_is_off_while_condition_inactive(replay):
    s = spec(mode="age", valid_max=120, above=1, off=0)
    assert replay.evaluate(s, 55) == pytest.approx(15)
    assert replay.evaluate(s, 90) == 0


def test_offset_shifts_position(replay):
    assert replay.evaluate(spec(offset=40), 5) == 30.0


def test_missing_series_returns_none(replay):
    assert replay.evaluate(ReplaySpec(source="nope"), 5) is None
    assert replay.evaluate(spec(valid_min=5000), 5) is None


def test_spec_from_config_reads_flat_keys():
    s = ReplaySpec.from_config({
        "replay_source": "2A1D0000", "replay_mode": "integral", "replay_valid_min": 0,
        "replay_valid_max": 120, "replay_above": 1, "replay_on": 75, "replay_scale": 0.5,
        "replay_bias": 2, "replay_min": 0, "replay_max": 100, "replay_offset": 30,
    }, default_source="385")
    assert (s.source, s.mode, s.valid_min, s.valid_max, s.above, s.on, s.off) == \
        ("2A1D0000", "integral", 0.0, 120.0, 1.0, 75.0, 0.0)
    assert (s.scale, s.bias, s.clamp_min, s.clamp_max, s.offset) == (0.5, 2.0, 0.0, 100.0, 30.0)
    assert ReplaySpec.from_config({}, default_source="385").source == "385"


def test_spec_rejects_unknown_mode():
    with pytest.raises(ValueError):
        ReplaySpec.from_config({"replay_mode": "sum"}, default_source="1")


def test_legacy_value_at_and_position_still_work(replay):
    assert replay.value_at("S", 25) == 3276.7
    assert replay.position(250) == 50
    assert 0 <= replay.current_offset() < replay.duration
