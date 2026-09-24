name: Download Googlesource Subdirectory

on:
  workflow_dispatch:
    inputs:
      url:
        description: "googlesource.com URL pointing to a subdirectory"
        required: true
        type: string

jobs:
  download:
    runs-on: ubuntu-latest

    steps:
      - name: Install git
        run: |
          sudo apt-get update
          sudo apt-get install -y git

      - name: Parse URL and download subdirectory
        shell: bash
        run: |
          set -euo pipefail

          URL='${{ inputs.url }}'

          # Example:
          # https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java
          ROOT_URL="${URL%%/+/ *}"
          if [[ "$URL" == *"/+/"* ]]; then
            ROOT_URL="${URL%%/+/ *}"
          fi

          # More robust split
          REPO_URL="${URL%%/+/ *}"
          SUBPATH="${URL#*/+/}"
          SUBPATH="${SUBPATH#*/}"   # drops ref part (refs/heads/main), leaving the path
          SUBPATH="${SUBPATH%%\?*}"

          if [[ -z "$SUBPATH" || "$SUBPATH" == "$URL" ]]; then
            echo "Could not parse subdirectory from URL: $URL"
            exit 1
          fi

          echo "Repo URL: $REPO_URL"
          echo "Subpath: $SUBPATH"

          git clone --depth 1 --filter=blob:none --sparse "$REPO_URL" repo
          cd repo
          git sparse-checkout set "$SUBPATH"

          mkdir -p ../artifact
          cp -R "$SUBPATH" "../artifact/"

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: googlesource-subdirectory
          path: artifact/
          if-no-files-found: error
