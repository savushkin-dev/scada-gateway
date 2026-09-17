"""
Параметры станции мойки (CIP) из прошивки ptusa: имя по индексу + наладочное значение.

Источник имён и индексов — репозиторий savushkin-r-d/ptusa_main:
  * PAC/common/cip_tech_def.h — #define P_* станции (PAR_MAIN), enum workParameters
    (RT_PAR_F линии: 1..117 рабочие, 119..136 статистика), enum SELFCLEAN_PARAMS;
  * PAC/common/mcaRec.h — enum RecipeValues (REC_PAR рецепта линии).
Соответствие полям базы каналов: RT_PAR_F[i] = rt_par_float[i],
REC_PAR[i] = lineRecipes->getValue(i - 1), PAR_MAIN[i] = par[i], PAR_SELFCLEAN[i].
Рабочий параметр линии с индексом i >= 16 — это загруженное значение рецепта
RecipeValues[i - 14] (PV1=16 ↔ RV_V1=2, …, P_WATCHDOG=116 ↔ RV_WATCHDOG=102).

В прошивке параметры по умолчанию нулевые и задаются при наладке. Значения ниже —
наладочные для станции такого масштаба и согласованы с архивом BN1_MCA1: расход на
линиях до 60 м³/ч (рецептурный 25), концентрация щёлочи на линии до 1.18 %,
давление p95 около 5 бар, объём танков около 8 м³ (уровень CLEVEL до 7930 л).
"""

# --- PAR_MAIN: параметры станции (#define P_* в cip_tech_def.h) ---------------
PAR_MAIN = {
    1: ("P_CZAD_S", 1.0),              # концентрация рабочего раствора щёлочи, %
    2: ("P_CMIN_S", 0.8),              # минимальная концентрация щёлочи, %
    3: ("P_CKANAL_S", 0.2),            # макс. концентрация щёлочи для канализации, %
    4: ("P_CZAD_K", 0.8),              # концентрация рабочего раствора кислоты, %
    5: ("P_CMIN_K", 0.6),
    6: ("P_CKANAL_K", 0.2),
    7: ("P_NAV_OVERREGULATE", 0.1),    # перерегулирование при наведении, %
    8: ("P_NAV_TOLERANCE", 0.1),       # допуск концентрации при наведении, %
    9: ("P_RESERVED", 0.0),
    10: ("P_BLOCK_ERRORS", 0.0),       # флаги блокировки ошибок модулей
    11: ("P_ALFK", 0.02),              # температурный коэффициент кислоты, 1/°C
    12: ("P_ALFS", 0.02),              # температурный коэффициент щёлочи, 1/°C
    13: ("P_K_S", 46.0),               # концентрация концентрированной щёлочи, %
    14: ("P_K_K", 55.0),               # концентрация концентрированной кислоты, %
    15: ("P_RO_S", 1.48),              # плотность концентрированной щёлочи, г/см³
    16: ("P_RO_K", 1.34),              # плотность концентрированной кислоты, г/см³
    17: ("P_CONC25S", 1.0),            # концентрация щёлочи в точке калибровки, %
    18: ("P_MS25S", 50.0),             # проводимость щёлочи в этой точке, мСм/см
    19: ("P_CONC25K", 1.0),
    20: ("P_MS25K", 55.0),
    21: ("P_MS25W", 0.5),              # проводимость воды, мСм/см
    22: ("P_TM_CHKC", 30.0),           # время измерения концентрации, с
    23: ("P_TM_CIRC_RR", 300.0),       # циркуляция перед изменением концентрации, с
    24: ("P_T_RR", 60.0),              # температура растворов при наведении, °C
    25: ("P_VTANKS", 8000.0),          # объём танка щёлочи, л
    26: ("P_VTANKK", 8000.0),          # объём танка кислоты, л
    27: ("P_VTANKW", 0.0),             # не используется
    28: ("P_PDNK", 100.0),             # производительность дозатора кислоты, л/ч
    29: ("P_PDNS", 100.0),             # производительность дозатора щёлочи, л/ч
    30: ("P_FLOW_RR", 10.0),           # расход при перемешивании растворов, м³/ч
    31: ("P_MAX_BULK_FOR_CAUSTIC", 85.0),  # макс. уровень танка щёлочи при наведении, %
    32: ("P_MAX_BULK_FOR_ACID", 85.0),
    33: ("P_CAUSTIC_TYPE", 1.0),       # тип щелочного раствора
    34: ("P_ACID_TYPE", 1.0),          # тип кислотного раствора
    35: ("P_DEZINFECTION_TYPE", 0.0),  # тип дезинфицирующего раствора
    36: ("P_CAUSTIC_SELECTED", 1.0),   # выбранный рецепт щелочного раствора
    37: ("P_ACID_SELECTED", 1.0),
    38: ("P_END_WASH_DELAY", 60.0),    # длительность операции 555 (завершение), с
    39: ("P_MIN_BULK_FOR_WATER", 10.0),  # мин. уровень в танке воды, %
    40: ("P_MIN_BULK_DELTA", 5.0),     # отклонение уровня в танке вторичной воды, %
}

# --- PAR_SELFCLEAN: параметры самоочистки станции (enum SELFCLEAN_PARAMS) -------
PAR_SELFCLEAN = {
    1: ("SCP_FLOW", 15.0),                 # расход мойки танков, м³/ч
    2: ("SCP_V_TW_PREDV", 500.0),          # объём предварительного ополаскивания ТВ, л
    3: ("SCP_T_TS_DRAIN", 300.0),          # время опорожнения танка щёлочи, с
    4: ("SCP_T_TK_DRAIN", 300.0),
    5: ("SCP_T_TW_DRAIN", 300.0),
    6: ("SCP_T_SCH_CIRC_PREDV", 120.0),    # предварительная циркуляция щёлочи, с
    7: ("SCP_T_K_CIRC_PREDV", 120.0),
    8: ("SCP_T_TS_CIRC", 600.0),           # циркуляция через моющую головку ТЩ, с
    9: ("SCP_T_TK_CIRC", 600.0),
    10: ("SCP_T_TW_CIRC", 300.0),
    11: ("SCP_LITERS_AFTER_LL_TS", 50.0),  # откачка после исчезновения НУ, л
    12: ("SCP_LITERS_AFTER_LL_TK", 50.0),
    13: ("SCP_LITERS_AFTER_LL_TW", 50.0),
    14: ("SCP_V_CLEAN_TS", 500.0),         # окончательное ополаскивание чистой водой, л
    15: ("SCP_V_CLEAN_TK", 500.0),
    16: ("SCP_V_CLEAN_TW", 500.0),
    17: ("SCP_V_PROM_TS", 300.0),          # промежуточное ополаскивание, л
    18: ("SCP_V_PROM_TK", 300.0),
    19: ("SCP_V_PROM_TW", 300.0),
}

# --- RecipeValues: рецепт линии (mcaRec.h), индекс 0-based ------------------------
_RECIPE_ORDER = [
    ("RV_IS_USED", 1.0), ("RV_TO_DEFAULTS", 0.0),
    ("RV_V1", 500.0), ("RV_V2", 500.0),                      # объёмы трассы, л
    ("RV_OBJ_TYPE", 1.0), ("RV_FLOW", 25.0),                 # тип объекта; расход, м³/ч
    ("RV_PODP_CIRC", 0.0), ("RV_DELTA_TR", 5.0),             # дельта подача/возврат, °C
    ("RV_T_WP", 40.0), ("RV_T_WSP", 40.0), ("RV_T_WKP", 40.0), ("RV_T_WOP", 20.0),
    ("RV_T_S", 75.0), ("RV_T_K", 65.0), ("RV_T_D", 90.0), ("RV_T_DEZSR", 20.0),
    ("RV_DOP_V_PR_OP", 100.0), ("RV_DOP_V_AFTER_S", 100.0),
    ("RV_DOP_V_AFTER_K", 100.0), ("RV_DOP_V_OK_OP", 100.0),
    ("RV_RET_STOP", 100.0), ("RV_V_RAB_ML", 50.0), ("RV_V_RET_DEL", 100.0),
    ("RV_TM_OP", 0.0), ("RV_TM_S", 900.0), ("RV_TM_K", 600.0),   # времена циркуляции, с
    ("RV_TM_S_SK", 900.0), ("RV_TM_K_SK", 600.0), ("RV_TM_D", 900.0),
    ("RV_TM_DEZSR", 600.0), ("RV_TM_DEZSR_INJECT", 60.0),
    ("RV_N_RET", 1.0), ("RV_N_UPR", 1.0), ("RV_OS", 0.0), ("RV_OBJ_EMPTY", 0.0),
    ("RV_PROGRAM_MASK", 63.0),
    ("RV_T_RINSING_CLEAN", 20.0), ("RV_V_RINSING_CLEAN", 300.0),
    ("RV_T_SANITIZER_RINSING", 20.0), ("RV_V_SANITIZER_RINSING", 300.0),
    ("RV_TM_MAX_TIME_OPORBACHOK", 120.0), ("RV_TM_RET_IS_EMPTY", 30.0),
    ("RV_V_LL_BOT", 50.0),
    ("RV_R_NO_FLOW", 2.0), ("RV_TM_R_NO_FLOW", 20.0),        # по умолчанию прошивки
    ("RV_TM_NO_FLOW_R", 20.0), ("RV_TM_NO_CONC", 20.0),
    # ПИД подогрева: задание — температура щелочной мойки.
    ("RV_PIDP_Z", 75.0), ("RV_PIDP_k", 1.0), ("RV_PIDP_Ti", 30.0), ("RV_PIDP_Td", 0.0),
    ("RV_PIDP_dt", 1000.0), ("RV_PIDP_dmax", 100.0), ("RV_PIDP_dmin", 0.0),
    ("RV_PIDP_AccelTime", 30.0), ("RV_PIDP_IsManualMode", 0.0), ("RV_PIDP_UManual", 0.0),
    ("RV_PIDP_Uk", 0.0),
    # ПИД расхода: задание — рецептурный расход.
    ("RV_PIDF_Z", 25.0), ("RV_PIDF_k", 1.0), ("RV_PIDF_Ti", 10.0), ("RV_PIDF_Td", 0.0),
    ("RV_PIDF_dt", 1000.0), ("RV_PIDF_dmax", 80.0), ("RV_PIDF_dmin", 0.0),
    ("RV_PIDF_AccelTime", 30.0), ("RV_PIDF_IsManualMode", 0.0), ("RV_PIDF_UManual", 0.0),
    ("RV_PIDF_Uk", 0.0),
    ("RV_TM_MAX_TIME_OPORCIP", 300.0),
    # Номера сигналов связи с объектом: 0 — сигнал не назначен.
    ("RV_SIGNAL_MEDIUM_CHANGE", 0.0), ("RV_SIGNAL_CAUSTIC", 0.0), ("RV_SIGNAL_ACID", 0.0),
    ("RV_SIGNAL_CIP_IN_PROGRESS", 0.0), ("RV_SIGNAL_CIPEND", 0.0),
    ("RV_SIGNAL_CIP_READY", 0.0), ("RV_SIGNAL_OBJECT_READY", 0.0),
    ("RV_SIGNAL_SANITIZER_PUMP", 0.0), ("RV_RESUME_CIP_ON_SIGNAL", 0.0),
    ("RV_SIGNAL_PUMP_CONTROL", 0.0), ("RV_SIGNAL_DESINSECTION", 0.0),
    ("RV_SIGNAL_OBJECT_PAUSE", 0.0), ("RV_SIGNAL_CIRCULATION", 0.0),
    ("RV_SIGNAL_PUMP_CAN_RUN", 0.0), ("RV_SIGNAL_PUMP_CONTROL_FEEDBACK", 0.0),
    ("RV_SIGNAL_RET_PUMP_SENSOR", 0.0), ("RV_RET_PUMP_SENSOR_DELAY", 5.0),
    ("RV_SIGNAL_IN_CIP_READY", 0.0), ("RV_SIGNAL_CIPEND2", 0.0),
    ("RV_SIGNAL_CAN_CONTINUE", 0.0), ("RV_SIGNAL_WATER", 0.0),
    ("RV_SIGNAL_PRERINSE", 0.0), ("RV_SIGNAL_INTERMEDIATE_RINSE", 0.0),
    ("RV_SIGNAL_POSTRINSE", 0.0), ("RV_SIGNAL_PUMP_STOPPED", 0.0),
    ("RV_SIGNAL_FLOW_TASK", 0.0), ("RV_SIGNAL_TEMP_TASK", 0.0),
    ("RV_SIGNAL_WASH_ABORTED", 0.0),
    ("RV_PRESSURE_CONTROL", 3.0),                             # задание давления, бар
    ("RV_DONT_USE_WATER_TANK", 0.0),
    ("RV_PIDP_MAX_OUT", 100.0), ("RV_PIDF_MAX_OUT", 100.0),
    ("RV_WATCHDOG", 0.0), ("RV_RESERV_START", 0.0),
    ("RV_WORKCENTER", 0.0),
]
# 105..114 — клапаны, открываемые рецептом; 115..119 — закрываемые. 0 — не назначен.
_RECIPE_ORDER += [(f"RV_VALVE_ON_{n}", 0.0) for n in range(1, 11)]
_RECIPE_ORDER += [(f"RV_VALVE_OFF_{n}", 0.0) for n in range(1, 6)]

RECIPE = {i: item for i, item in enumerate(_RECIPE_ORDER)}
assert RECIPE[47][0] == "RV_PIDP_Z" and RECIPE[104][0] == "RV_WORKCENTER"
assert len(RECIPE) == 120

RECIPE_BY_NAME = {name: value for name, value in RECIPE.values()}

# --- workParameters: рабочие параметры линии RT_PAR_F[1..117] ---------------------
# Вид: "run" — вычисляет контроллер (только чтение), "cmd" — команда оператора,
# "set" — уставка (значение загруженного рецепта). Источник для "run" описывается
# в tools/build_data_model.py по имени параметра.
_LINE_HEAD = [
    ("P_CONC_RATE", "run"), ("P_ZAD_PODOGR", "run"), ("P_ZAD_FLOW", "run"),
    ("P_VRAB", "run"), ("P_MAX_OPER_TM", "run"), ("P_OP_TIME_LEFT", "run"),
    ("P_CONC", "run"), ("P_SUM_OP", "run"), ("P_ZAD_CONC", "run"),
    ("P_LOADED_RECIPE", "run"), ("P_PROGRAM", "run"), ("P_CUR_REC", "cmd"),
    ("P_RET_STATE", "run"), ("P_SELECT_REC", "cmd"), ("P_SELECT_PRG", "cmd"),
]
LINE_PARAMS = {i + 1: item for i, item in enumerate(_LINE_HEAD)}
for _rt in range(16, 118):
    _name = RECIPE[_rt - 14][0].replace("RV_", "P_", 1)
    LINE_PARAMS[_rt] = (_name, "set")
# Параметры, которые в рецепте хранятся, но на линии вычисляются контроллером.
for _rt, _name in ((19, "P_FLOW"), (47, "P_OS"), (48, "P_OBJ_EMPTY"),
                   (71, "PIDP_Uk"), (82, "PIDF_Uk")):
    LINE_PARAMS[_rt] = (_name, "run")
assert len(LINE_PARAMS) == 117

# --- Статистика линии RT_PAR_F[119..136] (enum workParameters, STP_*) ------------
LINE_STATS = {
    119: ("STP_QAVS", None),       # средняя концентрация щёлочи — считается по архиву
    120: ("STP_QAVK", 0.55),       # средняя концентрация кислоты, %
    121: ("STP_WC", 1200.0),       # чистая вода, л
    122: ("STP_WS", 800.0),        # вторичная вода, л
    123: ("STP_LV", 0.0),
    124: ("STP_WC_INST_WS", 0.0),
    125: ("STP_WASH_START", 0.0),  # время начала мойки хранит сервер
    126: ("STP_STEPS_OVER", 0.0),
    127: ("STP_RESETSTEP", 0.0),
    128: ("STP_ERRCOUNT", 0.0),
    129: ("STP_USED_CAUSTIC", 35.0),  # использовано щёлочи, л
    130: ("STP_USED_ACID", 20.0),     # использовано кислоты, л
    131: ("STP_LAST_STEP_COUNTER", 0.0),
    132: ("STP_LAST_STEP", 0.0),
    133: ("STP_USED_HOTWATER", 0.0),
    134: ("STP_PODP_CAUSTIC", 0.0),
    135: ("STP_PODP_ACID", 0.0),
    136: ("STP_PODP_WATER", 0.0),
}

# --- Системные параметры PAC (SYSTEM) ---------------------------------------------
SYSTEM_PARAMS = {
    "P_RESTRICTIONS_MODE": 0,
    "P_RESTRICTIONS_MANUAL_TIME": 120,      # с
    "P_AUTO_PAUSE_OPER_ON_DEV_ERR": 0,
    "P_V_OFF_DELAY_TIME": 1000,             # мс
    "WASH_VALVE_SEAT_PERIOD": 3600,         # с
    "WASH_VALVE_UPPER_SEAT_TIME": 2000,     # мс
    "WASH_VALVE_LOWER_SEAT_TIME": 2000,     # мс
}

# --- Параметры приборов (enum CONSTANTS в PAC/common/device/device.h) -------------
DEVICE_PARAMS = {
    "P_CZ": 0.0,          # сдвиг нуля
    "P_DT": 1000.0,       # пороговый фильтр времени, мс
    "P_ERR": 0.0,         # аварийное значение
    "P_ON_TIME": 3000.0,  # время включения мотора, мс
    "P_MIN_FLOW": 0.0,    # шкала расходомера, м³/ч
    "P_MAX_FLOW": 80.0,
    "P_MAX_P": 0.6,       # давление настройки датчика уровня, бар
    "P_R": 0.8,           # радиус танка, м
    "P_H_CONE": 0.3,      # высота конуса танка, м
}
# Шкалы датчиков P_MIN_V / P_MAX_V по типу прибора.
SENSOR_RANGE = {
    "PT": (0.0, 16.0),    # давление, бар (архив: p95 около 5, максимум 10.4)
    "QT": (0.0, 200.0),   # проводимость, мСм/см (архив: до 42)
}
