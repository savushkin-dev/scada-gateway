-- Мёртвая зона: пока новое значение отличается от показанного меньше чем на delta,
-- канал держит показанное — дрожание аналогового сигнала не «шумит» на мониторе.
-- ctx.state — своя таблица у каждого канала, живёт между вызовами.
--
-- params: delta

function process(value, quality, ctx)
  local st = ctx.state
  if value == nil then
    st.shown = nil          -- после обрыва первое значение — сразу, без мёртвой зоны
    return nil, quality
  end
  if st.shown ~= nil and math.abs(value - st.shown) < ctx.params.delta then
    return st.shown, quality
  end
  st.shown = value
  return value, quality
end
