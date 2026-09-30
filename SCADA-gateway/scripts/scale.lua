-- Линейный пересчёт: оператор видит value * k + b (единицы, масштаб АЦП, смещение нуля).
-- Команда оператора пересчитывается обратно: (value - b) / k уходит в ПЛК.
--
-- params: k (по умолчанию 1), b (по умолчанию 0)

local function coeffs(ctx)
  return ctx.params.k or 1, ctx.params.b or 0
end

function process(value, quality, ctx)
  if value == nil then
    return nil, quality
  end
  local k, b = coeffs(ctx)
  return value * k + b, quality
end

function write(value, ctx)
  local k, b = coeffs(ctx)
  return (value - b) / k
end
