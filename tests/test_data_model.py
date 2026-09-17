"""
Тесты модели данных станции (tools/build_data_model.py, tools/check_data_model.py).

Два интеграционных теста работают на настоящих конфигах и архиве: конфиги должны
быть пересобраны после правки модели, а значения каналов — адекватны на всём архиве.
Остальные проверяют отдельные правила на синтетических тегах.
"""
import gzip
import pickle

import pytest

import build_data_model as model
import check_data_model as checker


@pytest.fixture(scope="module")
def archive_data():
    with gzip.open(model.ARCHIVE, "rb") as f:
        return pickle.load(f)


def test_configs_are_regenerated_after_model_changes(archive_data):
    sim = model.SIM.read_text(encoding="utf-8")
    ctl = model.CTL.read_text(encoding="utf-8")
    sim_new, ctl_new, _ = model.build(sim, ctl, archive_data)
    assert sim_new == sim, "replay_config.yaml устарел — запусти tools/build_data_model.py"
    assert ctl_new == ctl, "controllers.yaml устарел — запусти tools/build_data_model.py"


def test_channel_values_are_adequate_over_whole_archive():
    report = checker.check(model.SIM.read_text(encoding="utf-8"),
                           model.CTL.read_text(encoding="utf-8"), checker.load_replay())
    assert report.checked == 2517
    assert report.violations == []


def tag(device, field, dev_type, protocol="opcua", type_="float", address="1"):
    return {"name": address, "address": address, "type": type_, "protocol": protocol,
            "device": device, "field": field, "dev_type": dev_type, "access": "RO",
            "initial": 0, "noise_enabled": False, "drift_enabled": False}


@pytest.fixture
def ctx():
    everything = {model.cid(ss, i) for ss in list(model.SS.values()) + list(model.LINE_CTRL.values())
                  + list(model.LINE_PAR.values()) for i in range(0, 70)}
    return model.Context(series=everything, qavs={1: 0.62})


def written(t, ctx):
    return model.apply_plan(t, model.plan_for(t, ctx))


def test_modbus_is_read_only_even_for_setpoints(ctx):
    t = tag("LINE2M1", "P_ON_TIME", "M", protocol="modbus")
    assert model.plan_for(t, ctx).writable is True        # по смыслу уставка…
    assert written(t, ctx)["access"] == "RO"              # …но на Modbus запись запрещена
    assert written(dict(t, protocol="pac"), ctx)["access"] == "RW"


def test_sensor_reading_is_read_only_on_opcua_and_pac(ctx):
    for protocol in ("opcua", "pac"):
        assert written(tag("LINE1TE1", "V", "TE", protocol=protocol), ctx)["access"] == "RO"


def test_temperature_takes_series_of_its_own_sensor_cleaned_from_break_code(ctx):
    out = written(tag("LINE2TE2", "V", "TE", protocol="modbus"), ctx)
    assert out["replay_source"] == "2A1D0003"
    assert (out["replay_valid_min"], out["replay_valid_max"]) == (0.0, 120.0)


def test_disabled_line_is_idle(ctx):
    assert written(tag("LINE5TE1", "V", "TE"), ctx)["initial"] == model.AMBIENT_T
    valve = written(tag("L5_V3", "ST", "V", type_="int"), ctx)
    assert (valve["access"], valve["initial"], "generator" in valve) == ("RW", 0, False)
    closed = written(tag("LINE6VC14", "CLOSED", "VC", protocol="modbus", type_="int"), ctx)
    assert closed["initial"] == 1


def test_motor_command_follows_archive_and_stays_writable(ctx):
    out = written(tag("LINE1M1", "ST", "M", protocol="pac", type_="int"), ctx)
    assert (out["access"], out["replay_source"]) == ("RW", "2A0D0000")
    ret = written(tag("LINE2M1002", "ST", "M", type_="int"), ctx)
    assert (ret["replay_source"], ret["replay_above"]) == ("2A0D0001", 0.5)


def test_manual_mode_is_constant_auto_command(ctx):
    out = written(tag("LINE1V0", "M", "V", protocol="pac", type_="int"), ctx)
    assert (out["access"], out["initial"], "generator" in out) == ("RW", 0, False)


def test_flow_counter_is_integral_of_flow_in_litres(ctx):
    out = written(tag("LINE3FQT1", "V", "FQT", protocol="modbus"), ctx)
    assert (out["replay_source"], out["replay_mode"]) == ("2A3B0002", "integral")
    assert out["replay_scale"] == pytest.approx(1000 / 3600)


def test_line_task_is_recipe_value_while_operation_runs(ctx):
    out = written(tag("OBJECT2", "RT_PAR_F[3]", "Параметры_линии"), ctx)   # P_ZAD_FLOW
    assert (out["access"], out["replay_source"], out["replay_above"], out["replay_on"]) == \
        ("RO", "2A310001", 1.0, 25.0)
    op_time = written(tag("OBJECT1", "RT_PAR_F[6]", "Параметры_линии"), ctx)
    assert (op_time["replay_source"], op_time["replay_mode"]) == ("2A220001", "age")


def test_line_setpoint_is_recipe_constant(ctx):
    out = written(tag("OBJECT1", "RT_PAR_F[26]", "Параметры_линии"), ctx)   # P_T_S
    assert (out["access"], out["initial"], "generator" in out) == ("RW", 75.0, False)
    recipe = written(tag("OBJECT1", "REC_PAR[13]", "Редактируемый_рецепт_линии"), ctx)
    assert recipe["initial"] == 75.0


def test_line_state_keeps_negative_error_codes(ctx):
    out = written(tag("OBJECT3", "STATE", "Управляющие_каналы_линии", protocol="modbus", type_="int"), ctx)
    assert (out["access"], out["replay_source"]) == ("RO", "2A320000")
    assert "replay_min" not in out


def test_unknown_field_is_an_error_not_a_silent_default(ctx):
    with pytest.raises(ValueError):
        model.plan_for(tag("LINE1TE1", "BOGUS", "TE"), ctx)


def test_rendered_line_parses_back_to_same_tag(ctx):
    import yaml
    out = written(tag("LINE1VC14", "ST", "VC", protocol="pac", type_="int"), ctx)
    line = model.render(out, "    ")
    assert yaml.safe_load(line.strip()[2:]) == out
