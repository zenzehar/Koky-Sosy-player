#!/usr/bin/env bash
# Soky one-off test release cleanup. No automatic deletion and no credentials.
# Requires: gh auth login with repository release-delete permission.
set -euo pipefail

repo="${SOKY_REPO:-zenzehar/Koky-Sosy-player}"
mode="${1:---dry-run}"
case "$mode" in
  --dry-run|--delete-diagnostics|--delete-all-test-prereleases) ;;
  *) echo "Usage: $0 --dry-run | --delete-diagnostics | --delete-all-test-prereleases"; exit 2 ;;
esac

echo "Repository: $repo"
echo "Scope: $mode"
mapfile -t tags < <(
  gh api "repos/$repo/releases?per_page=100" --paginate \
    --jq '.[] | select(.prerelease == true) | .tag_name'
)

eligible=()
for tag in "${tags[@]}"; do
  case "$tag" in
    diagnostic-v0.1.11.47-android14-probe*) eligible+=("$tag") ;;
    v0.1.11.*)
      if [[ "$mode" == "--delete-all-test-prereleases" ]] &&
        [[ "$tag" != *milestone* ]] && [[ "$tag" != *golden* ]] &&
        [[ "$tag" != *approved* ]] && [[ "$tag" != v0.1.11.0* ]]; then
        eligible+=("$tag")
      fi ;;
  esac
done
if (( ${#eligible[@]} == 0 )); then
  echo "No eligible prerelease entries found."
  exit 0
fi

printf 'Eligible prereleases:\n'
printf ' - %s\n' "${eligible[@]}"
if [[ "$mode" == "--dry-run" ]]; then
  echo "DRY RUN only; no releases, tags or assets deleted."
  echo "After testing: --delete-diagnostics or --delete-all-test-prereleases."
  exit 0
fi

echo "This deletes listed GitHub releases/assets and their release tags."
echo "Does NOT erase downloaded APKs, Git history, forks, caches or workflow artifacts."
read -r -p "Type DELETE-TEST-RELEASES to proceed: " confirm
if [[ "$confirm" != "DELETE-TEST-RELEASES" ]]; then
  echo "Cancelled. No changes made."; exit 1
fi
for tag in "${eligible[@]}"; do
  prerelease="$(gh release view "$tag" --repo "$repo" --json isPrerelease --jq '.isPrerelease')"
  if [[ "$prerelease" != "true" ]]; then
    echo "Skipped protected non-prerelease: $tag"; continue
  fi
  gh release delete "$tag" --repo "$repo" --cleanup-tag --yes
  echo "Deleted prerelease and tag: $tag"
done
echo "Cleanup finished. Review Actions artifacts/branches separately if needed."
