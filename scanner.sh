#!/bin/bash
for i in {1..254}; do
    ip="10.0.22.$i"
    for port in 22; do
        ssh -vvv -p $port -o ConnectTimeout=2 -o StrictHostKeyChecking=no -o BatchMode=yes guest@$ip 2>&1 | grep -E "Local version string|KEX algorithms|host key algorithms|ciphers ctos|ciphers stoc|MACs ctos|MACs stoc|Authentications that can continue"
    done
done
