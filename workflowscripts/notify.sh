#!/usr/bin/env bash
set -uo pipefail

: "${COMMIT_LOG:?COMMIT_LOG env var required}"
: "${VERSION:?VERSION env var required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY env var required}"
: "${DISCORD_WEBHOOK:?DISCORD_WEBHOOK env var required}"
: "${SKIP_BUILD:=false}"
: "${APK_PATH:=}"
: "${CHECKSUM:=}"
: "${TELEGRAM_BOT_TOKEN:=}"
: "${TELEGRAM_CHAT_ID:=-1002117798698}"
: "${TELEGRAM_THREAD_ID:=7044}"

api_get() {
  curl -fsS "$@" || echo '[]'
}

fetch_user_details() {
  local login=$1
  local user_details
  user_details=$(api_get "https://api.github.com/users/$login")
  local name
  name=$(echo "$user_details" | jq -r '.name // .login // empty')
  [ -z "$name" ] && name="$login"
  local avatar_url
  avatar_url=$(echo "$user_details" | jq -r '.avatar_url // empty')
  echo "$name|$login|$avatar_url"
}

fetch_all_contributors() {
  local page=1
  local all="[]"
  while :; do
    local batch
    batch=$(api_get -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "https://api.github.com/repos/${GITHUB_REPOSITORY}/contributors?per_page=100&page=${page}")
    local count
    count=$(echo "$batch" | jq 'length' 2>/dev/null || echo 0)
    [ "$count" -eq 0 ] && break
    all=$(jq -c -n --argjson a "$all" --argjson b "$batch" '$a + $b')
    [ "$count" -lt 100 ] && break
    page=$((page + 1))
  done
  echo "$all"
}

declare -A additional_info
additional_info["ibo"]="\n Discord: <@951737931159187457>\n AniList: [takarealist112](<https://anilist.co/user/5790266/>)"
additional_info["aayush262"]="\n Discord: <@918825160654598224>\n AniList: [aayush262](<https://anilist.co/user/5144645/>)"
additional_info["rebel onion"]="\n Discord: <@714249925248024617>\n AniList: [rebelonion](<https://anilist.co/user/6077251/>)"
additional_info["Ankit Grai"]="\n Discord: <@1125628254330560623>\n AniList: [bheshnarayan](<https://anilist.co/user/6417303/>)"

declare -A contributor_colors
default_color="#bf2cc8"
contributor_colors["ibo"]="#ff9b46"
contributor_colors["aayush262"]="#5d689d"
contributor_colors["Sadwhy"]="#ff7e95"
contributor_colors["grayankit"]="#c51aa1"
contributor_colors["rebelonion"]="#d4e5ed"

hex_to_decimal() { printf '%d' "0x${1#"#"}"; }

declare -A recent_commit_counts
while read -r count name; do
  [ -z "$name" ] && continue
  recent_commit_counts["$name"]=$count
done < <(echo "$COMMIT_LOG" | sed 's/%0A/\n/g' | grep -oP '(?<=~)[^[]*' | sort | uniq -c | sort -rn)

echo "Fetching contributors from GitHub"
contributors=$(fetch_all_contributors)

sorted_contributors=$(for login in $(echo "$contributors" | jq -r '.[].login'); do
  user_info=$(fetch_user_details "$login")
  name=$(echo "$user_info" | cut -d'|' -f1)
  count=${recent_commit_counts["$name"]:-0}
  echo "$count|$login"
done | sort -rn | cut -d'|' -f2)

developers=""
committers_count=0
max_commits=0
top_contributor_count=0
top_contributor_avatar=""
embed_color=$(hex_to_decimal "$default_color")

while read -r login; do
  [ -z "$login" ] && continue
  user_info=$(fetch_user_details "$login")
  name=$(echo "$user_info" | cut -d'|' -f1)
  login=$(echo "$user_info" | cut -d'|' -f2)
  avatar_url=$(echo "$user_info" | cut -d'|' -f3)

  commit_count=${recent_commit_counts["$name"]:-0}
  if [ "$commit_count" -gt 0 ]; then
    if [ "$commit_count" -gt "$max_commits" ]; then
      max_commits=$commit_count
      top_contributors=("$login")
      top_contributor_count=1
      top_contributor_avatar="$avatar_url"
      embed_color=$(hex_to_decimal "${contributor_colors[$name]:-$default_color}")
    elif [ "$commit_count" -eq "$max_commits" ]; then
      top_contributors+=("$login")
      top_contributor_count=$((top_contributor_count + 1))
      embed_color=$(hex_to_decimal "$default_color")
    fi

    branch_commit_count=$(git log --author="$login" --author="$name" --oneline | awk '!seen[$0]++' | wc -l)

    extra_info="${additional_info[$name]:-}"
    if [ -n "$extra_info" ]; then
      extra_info=$(echo "$extra_info" | sed 's/\\n/\n- /g')
    fi

    developer_entry="◗ **${name}** ${extra_info}
- Github: [${login}](https://github.com/${login})
- Commits: ${branch_commit_count}"

    if [ -n "$developers" ]; then
      developers="${developers}
${developer_entry}"
    else
      developers="${developer_entry}"
    fi
    committers_count=$((committers_count + 1))
  fi
done <<< "$sorted_contributors"

if [ "$top_contributor_count" -eq 1 ]; then
  thumbnail_url="$top_contributor_avatar"
else
  thumbnail_url="https://i.imgur.com/5o3Y9Jb.gif"
  embed_color=$(hex_to_decimal "$default_color")
fi

max_length=1000
commit_messages=$(echo "$COMMIT_LOG" | sed 's/%0A/\n/g; s/^/\n/')
if [ ${#developers} -gt $max_length ]; then
  developers="${developers:0:$max_length}... (truncated)"
fi
if [ ${#commit_messages} -gt $max_length ]; then
  commit_messages="${commit_messages:0:$max_length}... (truncated)"
fi

footer_text="Version $VERSION"
if [ -n "$CHECKSUM" ]; then
  footer_text="Version $VERSION | SHA256: ${CHECKSUM:0:12}..."
fi

discord_data=$(jq -nc \
  --arg field_value "$commit_messages" \
  --arg author_value "$developers" \
  --arg footer_text "$footer_text" \
  --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" \
  --arg thumbnail_url "$thumbnail_url" \
  --arg embed_color "$embed_color" \
  '{
    "content": "<@&1225347048321191996>",
    "embeds": [
      {
        "title": "New Alpha-Build dropped",
        "color": $embed_color,
        "fields": [
          { "name": "Commits:", "value": $field_value, "inline": true },
          { "name": "Developers:", "value": $author_value, "inline": false }
        ],
        "footer": { "text": $footer_text },
        "timestamp": $timestamp,
        "thumbnail": { "url": $thumbnail_url }
      }
    ],
    "attachments": []
  }')

curl -fsS -H "Content-Type: application/json" -d "$discord_data" "$DISCORD_WEBHOOK" >/dev/null \
  && echo "Discord embed sent" \
  || echo "::warning::Discord embed failed to send"

if [ "$SKIP_BUILD" != "true" ] && [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
  curl -fsS -F "dantotsu_debug=@${APK_PATH}" "$DISCORD_WEBHOOK" >/dev/null \
    && echo "APK uploaded to Discord" \
    || echo "::warning::APK upload to Discord failed"
else
  echo "Skipping APK upload to Discord (SKIP_BUILD=$SKIP_BUILD or APK not found)"
fi

telegram_commit_messages=$(echo "$COMMIT_LOG" | sed 's/%0A/\n/g' | while read -r line; do
  message=$(echo "$line" | sed -E 's/● (.*) ~(.*) \[֍\]\((.*)\)/● \1 ~\2 <a href="\3">֍<\/a>/')
  message=$(echo "$message" | sed -E 's/\[#([0-9]+)\]\((https:\/\/github\.com\/[^)]+)\)/<a href="\2">#\1<\/a>/g')
  echo "$message"
done)
telegram_commit_messages="<blockquote>${telegram_commit_messages}</blockquote>"

echo "$developers" > dev_info.txt
chmod +x workflowscripts/tel_parser.sed
./workflowscripts/tel_parser.sed dev_info.txt >> output.txt
dev_info_tel=$(< output.txt)
telegram_dev_info="<blockquote>${dev_info_tel}</blockquote>"

if [ "$SKIP_BUILD" != "true" ] && [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
  caption="New Alpha-Build dropped 🔥

Commits:
${telegram_commit_messages}
Dev:
${telegram_dev_info}
version: ${VERSION}"
  if [ -n "$CHECKSUM" ]; then
    caption="${caption}
SHA256: ${CHECKSUM}"
  fi

  curl -fsS -X POST \
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
    -F "chat_id=${TELEGRAM_CHAT_ID}" \
    -F "message_thread_id=${TELEGRAM_THREAD_ID}" \
    -F "document=@${APK_PATH}" \
    -F "caption=${caption}" \
    -F "parse_mode=HTML" >/dev/null \
    && echo "APK uploaded to Telegram" \
    || echo "::warning::APK upload to Telegram failed"
else
  echo "Skipping Telegram upload (SKIP_BUILD=$SKIP_BUILD or missing bot token/APK)"
fi
