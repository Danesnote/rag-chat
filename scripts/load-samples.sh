#!/usr/bin/env bash
# samples/ 의 예시 문서(가상의 회사 규정)를 문서 적재 API 로 올린다.
#   사용법: ./scripts/load-samples.sh [BASE_URL]   (기본 http://localhost:8080)
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
DIR="$(cd "$(dirname "$0")/../samples" && pwd)"

upload() {
  local file="$1" title="$2" version="$3" effective="$4"
  echo "▶ ${title}"
  curl -sS -f -X POST "${BASE_URL}/api/documents/upload" \
    -F "file=@${DIR}/${file}" \
    -F "title=${title}" \
    -F "version=${version}" \
    -F "effectiveDate=${effective}"
  echo
}

upload leave-policy.txt          "휴가 규정"           "3" "2025-01-01"
upload travel-expense.txt        "여비 지급 규정"      "2" "2025-03-01"
upload information-security.txt  "정보보안 지침"       "5" "2025-01-01"
upload corporate-card.txt        "법인카드 사용 지침"  "1" "2025-02-01"
upload remote-work.md            "재택근무 운영 안내"  "1" "2025-01-01"

echo "완료. 문서 목록: curl ${BASE_URL}/api/documents"
