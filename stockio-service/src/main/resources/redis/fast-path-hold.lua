-- All-or-nothing provisional hold.
-- KEYS: stock, reservation, expiry repeated per line
-- ARGV[1] ttl seconds, ARGV[2] createdAt, ARGV[3] JSON lines
local ttl = tonumber(ARGV[1])
local createdAt = ARGV[2]
local lines = cjson.decode(ARGV[3])
if #KEYS ~= #lines * 3 then
  return redis.error_reply('key count mismatch')
end

local allExist = true
for i, _ in ipairs(lines) do
  if redis.call('EXISTS', KEYS[(i - 1) * 3 + 2]) == 0 then
    allExist = false
  end
end
if allExist and #lines > 0 then
  return 2
end
for i, _ in ipairs(lines) do
  if redis.call('EXISTS', KEYS[(i - 1) * 3 + 2]) == 1 then
    return redis.error_reply('partial provisional hold')
  end
end

local pending = {}
for i, line in ipairs(lines) do
  local stockKey = KEYS[(i - 1) * 3 + 1]
  pending[stockKey] = (pending[stockKey] or 0) + tonumber(line.qty)
end
for stockKey, qty in pairs(pending) do
  local available = tonumber(redis.call('GET', stockKey) or '0')
  if available < qty then
    return 0
  end
end

for i, line in ipairs(lines) do
  local stockKey = KEYS[(i - 1) * 3 + 1]
  local reservationKey = KEYS[(i - 1) * 3 + 2]
  local expiryKey = KEYS[(i - 1) * 3 + 3]
  redis.call('DECRBY', stockKey, line.qty)
  redis.call('HSET', reservationKey,
    'sku', line.sku,
    'warehouse', line.warehouse,
    'qty', tostring(line.qty),
    'requestId', line.requestId,
    'orderId', line.orderId,
    'orderLineId', line.orderLineId,
    'status', 'tentative',
    'createdAt', createdAt)
  redis.call('SET', expiryKey, line.requestId, 'EX', ttl)
end
return 1
