#!/bin/sh
IFS= read -r request || :
if printf 'escaped' > /workspace/escape 2>/dev/null; then
    printf '{"outcome":"blocked","payload":{"readOnly":false}}'
    exit 1
fi
printf '{"outcome":"answered","payload":{"readOnly":true}}'
