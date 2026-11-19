package me.rerere.workspace

/**
 * One-tap package bundles for the sandboxed PRoot workspace (Ubuntu base by default, Alpine
 * supported). Each profile maps to distro packages plus pip packages; the generated script
 * detects apt/apk at runtime, is idempotent, and logs to /root/.rikkalaw/bootstrap.log.
 */
enum class BootstrapProfile(
    val id: String,
    val label: String,
    val description: String,
    val apt: List<String>,
    val apk: List<String>,
    val pip: List<String>,
) {
    CORE(
        "core", "Core tools",
        "python3, pip, venv, curl, wget, git, jq, zip/unzip",
        apt = listOf("python3", "python3-pip", "python3-venv", "curl", "wget", "git", "jq", "ca-certificates", "zip", "unzip", "file", "less", "nano"),
        apk = listOf("bash", "python3", "py3-pip", "curl", "wget", "git", "jq", "ca-certificates", "zip", "unzip", "file", "less", "nano"),
        pip = emptyList(),
    ),
    RESEARCH(
        "research", "Research & PDFs",
        "requests, beautifulsoup4, lxml, pdfplumber, pypdf, poppler-utils (pdftotext)",
        apt = listOf("python3-requests", "python3-bs4", "python3-lxml", "poppler-utils"),
        apk = listOf("py3-requests", "py3-beautifulsoup4", "py3-lxml", "poppler-utils"),
        pip = listOf("pdfplumber", "pypdf"),
    ),
    DRAFTING(
        "drafting", "Drafting",
        "python-docx, docxtpl, pandoc (markdown ⇄ docx)",
        apt = listOf("pandoc", "python3-docx"),
        apk = listOf("pandoc-cli"),
        pip = listOf("python-docx", "docxtpl"),
    ),
    OCR(
        "ocr", "OCR",
        "tesseract (English), ocrmypdf, ghostscript — offline OCR for scanned records",
        apt = listOf("tesseract-ocr", "tesseract-ocr-eng", "ocrmypdf", "ghostscript"),
        apk = listOf("tesseract-ocr", "tesseract-ocr-data-eng", "ghostscript"),
        pip = listOf("ocrmypdf"),
    ),
    CITATIONS(
        "citations", "Citations",
        "eyecite + reporters-db + courts-db (Free Law Project citation parsing)",
        apt = emptyList(),
        apk = emptyList(),
        pip = listOf("eyecite", "reporters-db", "courts-db"),
    );

    companion object {
        fun byId(id: String): BootstrapProfile? = entries.firstOrNull { it.id.equals(id.trim(), true) }

        /**
         * POSIX sh script installing [profiles] (CORE is always included). pip packages that a
         * distro package already provides are skipped by pip itself ("already satisfied").
         */
        /** Marker the app parses for a progress bar: `## STEP <i>/<n> <label>`. */
        const val STEP_PREFIX = "## STEP "
        const val DONE_MARKER = "== done"
        const val FAILED_MARKER = "== failed:"
        /** Path of the generated script inside the rootfs (the workspace files area). */
        const val SCRIPT_PATH = "/workspace/.rikkalaw/bootstrap.sh"

        /**
         * POSIX sh script installing [profiles] (CORE is always included).
         *
         * Distro packages are installed in ONE apt/apk transaction (per-package installs are
         * very slow under PRoot because every call re-reads the package database); only if the
         * batch fails does it fall back to one package at a time so a single missing package
         * does not block the rest. Package-manager output is streamed to stdout (and appended
         * to /root/.rikkalaw/bootstrap.log) so the app can show live progress.
         */
        fun script(profiles: Collection<BootstrapProfile>): String {
            val selected = (listOf(CORE) + profiles).distinct().sortedBy { it.ordinal }
            fun q(list: List<String>) = list.joinToString(" ")
            val apt = selected.flatMap { it.apt }.distinct()
            val apk = selected.flatMap { it.apk }.distinct()
            // OCR on apt uses the distro ocrmypdf; apk has no package so pip provides it.
            val pipApt = selected.flatMap { it.pip }.distinct().filterNot { it == "ocrmypdf" }
            val pipApk = selected.flatMap { it.pip }.distinct()
            val d = "$"
            return """
                |#!/bin/sh
                |# RikkaLaw workspace bootstrap: ${selected.joinToString(", ") { it.id }}
                |mkdir -p /root/.rikkalaw
                |LOG=/root/.rikkalaw/bootstrap.log
                |RC=/root/.rikkalaw/rc
                |echo "== bootstrap ${d}(date) : ${selected.joinToString(" ") { it.id }}" | tee -a "${d}LOG"
                |export DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true TZ=Etc/UTC
                |export PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_ROOT_USER_ACTION=ignore PIP_PROGRESS_BAR=off
                |# run CMD..., streaming output to stdout + log, and return its exit status
                |run() { { "${d}@"; echo ${d}? > "${d}RC"; } 2>&1 | tee -a "${d}LOG"; return "${d}(cat "${d}RC" 2>/dev/null || echo 1)"; }
                |if command -v apt-get >/dev/null 2>&1; then PM=apt
                |elif command -v apk >/dev/null 2>&1; then PM=apk
                |else echo "Unsupported rootfs: need apt-get (Ubuntu/Debian) or apk (Alpine)"; echo "$FAILED_MARKER unsupported-rootfs"; exit 2; fi
                |FAILED=""
                |N=4
                |step() { echo "$STEP_PREFIX${d}1/${d}N ${d}2"; }
                |if [ "${d}PM" = apt ]; then
                |  ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime 2>/dev/null; echo Etc/UTC > /etc/timezone 2>/dev/null
                |  APT="apt-get -y -q -o APT::Sandbox::User=root -o Dpkg::Options::=--force-confnew -o Dpkg::Use-Pty=0"
                |  step 1 "Updating package lists"
                |  run ${d}APT update || FAILED="${d}FAILED apt-update"
                |  TODO=""
                |  for p in ${q(apt)}; do dpkg -s "${d}p" >/dev/null 2>&1 || TODO="${d}TODO ${d}p"; done
                |  step 2 "Installing system packages:${d}TODO"
                |  if [ -n "${d}TODO" ]; then
                |    if ! run ${d}APT install --no-install-recommends ${d}TODO; then
                |      echo "Batch install failed; retrying one package at a time"
                |      run ${d}APT -f install
                |      for p in ${d}TODO; do
                |        dpkg -s "${d}p" >/dev/null 2>&1 && continue
                |        echo ">> ${d}p"
                |        run ${d}APT install --no-install-recommends "${d}p" || FAILED="${d}FAILED ${d}p"
                |      done
                |    fi
                |  else echo "All system packages already installed"; fi
                |  run ${d}APT clean
                |  PIPS="${q(pipApt)}"
                |else
                |  step 1 "Updating package index"
                |  run apk update || FAILED="${d}FAILED apk-update"
                |  step 2 "Installing system packages"
                |  if ! run apk add --no-cache ${q(apk)}; then
                |    for p in ${q(apk)}; do run apk add --no-cache "${d}p" || FAILED="${d}FAILED ${d}p"; done
                |  fi
                |  PIPS="${q(pipApk)}"
                |fi
                |step 3 "Installing Python packages: ${d}PIPS"
                |if [ -n "${d}PIPS" ]; then
                |  if command -v python3 >/dev/null 2>&1; then
                |    if ! run python3 -m pip install --no-cache-dir --break-system-packages ${d}PIPS; then
                |      for p in ${d}PIPS; do
                |        run python3 -m pip install --no-cache-dir --break-system-packages "${d}p" || FAILED="${d}FAILED pip:${d}p"
                |      done
                |    fi
                |  else FAILED="${d}FAILED python3-missing"; fi
                |fi
                |step 4 "Verifying"
                |echo "== installed tools"
                |for t in python3 pip3 curl wget git jq pandoc tesseract ocrmypdf pdftotext; do
                |  if command -v "${d}t" >/dev/null 2>&1; then echo "  ok  ${d}t"; fi
                |done
                |if command -v python3 >/dev/null 2>&1; then
                |  python3 - <<'PY'
                |import importlib
                |for m in ["requests", "bs4", "lxml", "pdfplumber", "pypdf", "docx", "docxtpl", "eyecite"]:
                |    try:
                |        importlib.import_module(m); print("  ok  python:" + m)
                |    except Exception:
                |        pass
                |PY
                |fi
                |if [ -n "${d}FAILED" ]; then echo "$FAILED_MARKER${d}FAILED (log: ${d}LOG)"; exit 1; fi
                |echo "$DONE_MARKER (log: ${d}LOG)"
                |""".trimMargin()
        }

        /** Parses the last `## STEP i/n label` line from live output. */
        fun parseStep(output: String): Triple<Int, Int, String>? {
            val line = output.lineSequence().lastOrNull { it.startsWith(STEP_PREFIX) } ?: return null
            val m = Regex("""^## STEP (\d+)/(\d+) ?(.*)$""").find(line) ?: return null
            return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].trim())
        }
    }
}
