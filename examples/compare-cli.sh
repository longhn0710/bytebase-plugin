#!/bin/sh
# Diagnostic only: CheckRelease and metadata reads, never rollout or SQL execution.
set -eu
set +x
: "${BYTEBASE_URL:?}" "${BYTEBASE_PROJECT:?}" "${BYTEBASE_TARGET:?}"
: "${BYTEBASE_SERVICE_ACCOUNT:?}" "${BYTEBASE_SERVICE_ACCOUNT_SECRET:?}"
mkdir -p .comparison
set +e
bytebase-action check --url "$BYTEBASE_URL" --project "$BYTEBASE_PROJECT" \
  --targets "$BYTEBASE_TARGET" --file-pattern '.comparison/V3__add_source_system.sql' \
  --check-release SKIP --output .comparison/cli.json > .comparison/cli.log 2>&1
cli_status=$?
set -e
printf '%s\n' "$cli_status" > .comparison/cli-exit-code.txt
# The token stays in shell memory. curl receives it through stdin config, not argv/files.
token=$(jq -nc '{email:env.BYTEBASE_SERVICE_ACCOUNT,password:env.BYTEBASE_SERVICE_ACCOUNT_SECRET}' |
  curl --silent --show-error --fail --max-time 30 -H 'Content-Type: application/json' \
    -H 'Connect-Protocol-Version: 1' --data-binary @- "$BYTEBASE_URL/bytebase.v1.AuthService/Login" |
  jq -er '.token | select(length > 0)')
rpc() {
  method=$1
  payload=$2
  label=$3
  response=$({
    printf 'header = "Content-Type: application/json"\n'
    printf 'header = "Connect-Protocol-Version: 1"\n'
    printf 'header = "Authorization: Bearer %s"\n' "$token"
  } | curl --silent --show-error --max-time 30 --config - --data-binary "$payload" \
      -w '\n%{http_code}' "$BYTEBASE_URL/bytebase.v1.$method")
  printf '%s\n' "$response" | tail -n 1 > ".comparison/rpc-$label-http.txt"
  printf '%s\n' "$response" | sed '$d' | \
    jq '{code,message,name,email,project,state,effectiveEnvironment,syncStatus,engine}' > ".comparison/rpc-$label.json"
  unset response
}
rpc UserService/GetCurrentUser '{}' identity
rpc DatabaseService/GetDatabase "$(jq -nc '{name:env.BYTEBASE_TARGET}')" database
rpc ProjectService/GetProject "$(jq -nc '{name:env.BYTEBASE_PROJECT}')" project
rpc InstanceService/GetInstance "$(jq -nc --arg name "${BYTEBASE_TARGET%/databases/*}" '{name:$name}')" instance
unset token
