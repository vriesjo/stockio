-- Restore availability for holds that still exist. ARGV[1] is unused.
local lines = #KEYS / 3
for i = 1, lines do
  local stockKey = KEYS[(i - 1) * 3 + 1]
  local reservationKey = KEYS[(i - 1) * 3 + 2]
  local expiryKey = KEYS[(i - 1) * 3 + 3]
  if redis.call('EXISTS', reservationKey) == 1 then
    local qty = tonumber(redis.call('HGET', reservationKey, 'qty') or '0')
    redis.call('INCRBY', stockKey, qty)
    redis.call('DEL', reservationKey)
  end
  redis.call('DEL', expiryKey)
end
return 1
