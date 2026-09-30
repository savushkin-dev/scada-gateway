-- Отсев кода обрыва датчика: значение вне [valid_min, valid_max] — кадр BAD без значения.
-- Датчики ptusa в обрыве пишут служебный код (у температуры ±3276.7) — оператору и
-- графику это не температура, а «нет данных».
--
-- params: valid_min, valid_max

function process(value, quality, ctx)
  if value == nil then
    return nil, quality
  end
  local p = ctx.params
  if (p.valid_min and value < p.valid_min) or (p.valid_max and value > p.valid_max) then
    return nil, "BAD"
  end
  return value, quality
end
