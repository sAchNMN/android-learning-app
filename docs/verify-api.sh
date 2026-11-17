#!/usr/bin/env bash
# ============================================================
# 接口冒烟测试：按客户端真实调用顺序打一遍 P2 的全部接口
#
# 前置：后端已启动（默认 http://localhost:8080）、SQL 已导入
# 用法：bash docs/verify-api.sh
#       BASE=http://192.168.1.10:8080 bash docs/verify-api.sh
#
# 只断言「code == 200」。业务断言（金额对不对）留给装机验证，
# 这里的目标是快速确认「接口通不通、SQL 有没有报列不存在」。
# ============================================================
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0

# Windows 上可能只有 python 或只有 python3
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
    echo "找不到 python，脚本用它解析 JSON。请先装 python 或改脚本换 jq。"
    exit 1
fi

json() {
    # 从 stdin 取 JSON，取字段（不依赖 jq）
    "$PY" -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null
}

check() {
    local name="$1"
    local body="$2"

    local code
    code=$(printf '%s' "$body" | json "['code']")
    local msg
    msg=$(printf '%s' "$body" | json "['msg']")

    if [ "$code" = "200" ]; then
        PASS=$((PASS + 1))
        printf '  \033[32mPASS\033[0m %s\n' "$name"
    else
        FAIL=$((FAIL + 1))
        printf '  \033[31mFAIL\033[0m %s  → code=%s msg=%s\n' "$name" "${code:-?}" "$msg"
        # 出错时把原始响应截一段，方便对原因
        printf '       %s\n' "$(printf '%s' "$body" | head -c 300)"
    fi
}

get() {
    curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$BASE$1"
}

post() {
    local path="$1"
    local body="${2:-}"
    if [ -z "$body" ]; then
        body='{}'
    fi
    curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d "$body" "$BASE$path"
}

delete() {
    curl -s --noproxy '*' -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE$1"
}

echo "== 1. 登录 =="
LOGIN=$(curl -s --noproxy '*' -X POST "$BASE/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}')
TOKEN=$(printf '%s' "$LOGIN" | json "['token']")

if [ -z "$TOKEN" ]; then
    echo "  登录失败，检查后端是否启动、Redis 是否运行（token 存在 Redis）"
    printf '%s\n' "$LOGIN" | head -c 400
    exit 1
fi
echo "  token: ${TOKEN:0:16}…"

echo "== 2. 首页与店铺 =="
check "GET  /app/home/index" "$(get /app/home/index)"
check "GET  /app/shop/category/list" "$(get /app/shop/category/list)"
check "GET  /app/shop/page" "$(get '/app/shop/page?pageNum=1&pageSize=10')"
check "GET  /app/shop/page?sort=rating" "$(get '/app/shop/page?pageNum=1&pageSize=10&sort=rating')"
check "GET  /app/shop/page?categoryId=1" "$(get '/app/shop/page?pageNum=1&pageSize=10&categoryId=1')"
check "GET  /app/shop/page?keyword=牛肉" "$(get '/app/shop/page?pageNum=1&pageSize=10&keyword=%E7%89%9B%E8%82%89')"

SHOP=$(get /app/shop/1)
check "GET  /app/shop/1（含 groups 与 specs）" "$SHOP"

# 从店铺详情里挑一个「无规格」和「有规格」的菜，供下单用
read -r PLAIN_DISH SPEC_DISH SPEC_OPTIONS <<EOF
$(printf '%s' "$SHOP" | "$PY" -c "
import sys, json
d = json.load(sys.stdin)['data']
plain, spec, opts = 0, 0, []
for g in d['groups']:
    for dish in g['dishes']:
        if dish['hasSpec'] and spec == 0:
            spec = dish['dishId']
            # 每个必选组取第一个选项
            for s in dish['specs']:
                if s['required'] and s['options']:
                    opts.append(s['options'][0]['optionId'])
        if not dish['hasSpec'] and plain == 0:
            plain = dish['dishId']
print(plain, spec, ','.join(str(o) for o in opts))
" 2>/dev/null)
EOF

echo "== 3. 搜索与收藏 =="
check "GET  /app/search/hot" "$(get /app/search/hot)"
check "GET  /app/search?type=shop" "$(get '/app/search?keyword=%E7%89%9B%E8%82%89&type=shop')"
check "GET  /app/search?type=dish" "$(get '/app/search?keyword=%E8%B1%86%E8%85%90&type=dish')"
check "POST /app/favorite/1" "$(post /app/favorite/1)"
check "GET  /app/favorite/list" "$(get '/app/favorite/list?pageNum=1&pageSize=10')"
check "DEL  /app/favorite/1（幂等）" "$(delete /app/favorite/1)"

echo "== 4. 地址 =="
ADDRESS=$(get /app/address/list)
check "GET  /app/address/list" "$ADDRESS"
ADDRESS_ID=$(printf '%s' "$ADDRESS" | "$PY" -c "
import sys, json
rows = json.load(sys.stdin)['data']
print(rows[0]['addressId'] if rows else '')
" 2>/dev/null)

if [ -z "$ADDRESS_ID" ]; then
    echo "  没有收货地址，跳过结算与下单。请先在 App 里新建一个地址，或直接调 POST /app/address。"
    echo "  结果：$PASS 通过，$FAIL 失败"
    exit $((FAIL > 0 ? 1 : 0))
fi

echo "== 5. 结算预览 =="
ITEMS="[{\"dishId\":$PLAIN_DISH,\"quantity\":1}]"
if [ "$SPEC_DISH" != "0" ] && [ -n "$SPEC_OPTIONS" ]; then
    ITEMS="[{\"dishId\":$PLAIN_DISH,\"quantity\":1},{\"dishId\":$SPEC_DISH,\"quantity\":1,\"optionIds\":[$SPEC_OPTIONS]}]"
fi
echo "  下单内容：$ITEMS"

PREVIEW=$(post /app/order/preview "{\"shopId\":1,\"items\":$ITEMS,\"addressId\":$ADDRESS_ID}")
check "POST /app/order/preview" "$PREVIEW"
printf '%s' "$PREVIEW" | "$PY" -c "
import sys, json
d = json.load(sys.stdin).get('data') or {}
print('  小计 %s / 配送 %s / 实付 %s / 还差 %s' % (
    d.get('totalAmount'), d.get('deliveryFee'), d.get('payAmount'), d.get('shortOfMin')))
print('  配送档位：' + '、'.join(o['label'] for o in d.get('expectOptions') or []))
" 2>/dev/null

echo "== 6. 下单（含幂等与规格校验）=="
TOKEN_KEY="verify-$(date +%s)"
ORDER_BODY="{\"clientToken\":\"$TOKEN_KEY\",\"shopId\":1,\"items\":$ITEMS,\"addressId\":$ADDRESS_ID,\"expectTime\":\"asap\"}"

ORDER=$(post /app/order/create "$ORDER_BODY")
check "POST /app/order/create" "$ORDER"
ORDER_ID=$(printf '%s' "$ORDER" | "$PY" -c "
import sys, json
d = json.load(sys.stdin).get('data') or {}
print((d.get('order') or {}).get('orderId', ''))
" 2>/dev/null)

AGAIN=$(post /app/order/create "$ORDER_BODY")
if printf '%s' "$AGAIN" | json "['code']" | grep -qv '^200$'; then
    PASS=$((PASS + 1))
    printf '  \033[32mPASS\033[0m 重复 clientToken 被拒绝（幂等生效）\n'
else
    FAIL=$((FAIL + 1))
    printf '  \033[31mFAIL\033[0m 重复 clientToken 居然成功了，幂等没生效\n'
fi

if [ -n "$ORDER_ID" ]; then
    echo "== 7. 订单 =="
    check "GET  /app/order/page" "$(get '/app/order/page?status=all&pageNum=1&pageSize=10')"
    check "GET  /app/order/page?status=unpaid" "$(get '/app/order/page?status=unpaid&pageNum=1&pageSize=10')"
    check "GET  /app/order/$ORDER_ID（含 timeline）" "$(get "/app/order/$ORDER_ID")"
    check "POST /app/order/$ORDER_ID/pay" "$(post "/app/order/$ORDER_ID/pay")"

    # 再支付一次应该失败：状态已从 0 变成 1/2
    DUP=$(post "/app/order/$ORDER_ID/pay")
    if printf '%s' "$DUP" | json "['code']" | grep -qv '^200$'; then
        PASS=$((PASS + 1))
        printf '  \033[32mPASS\033[0m 重复支付被拒绝（CAS 生效）\n'
    else
        FAIL=$((FAIL + 1))
        printf '  \033[31mFAIL\033[0m 重复支付成功了，状态机有问题\n'
    fi
fi

echo
echo "结果：$PASS 通过，$FAIL 失败"
[ "$FAIL" -eq 0 ]
