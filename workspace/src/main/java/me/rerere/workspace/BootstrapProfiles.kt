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
        fun script(profiles: Collection<BootstrapProfile>): String {
            val selected = (listOf(CORE) + profiles).distinct().sortedBy { it.ordinal }
            fun q(list: List<String>) = list.joinToString(" ")
            val apt = selected.flatMap { it.apt }.distinct()
            val apk = selected.flatMap { it.apk }.distinct()
            // OCR on apk has no ocrmypdf package; everywhere else the distro package is preferred.
            val pipApt = selected.flatMap { it.pip }.distinct().filterNot { it == "ocrmypdf" }
            val pipApk = selected.flatMap { it.pip }.distinct()
            return """
                |#!/bin/sh
                |# RikkaLaw workspace bootstrap: ${selected.joinToString(", ") { it.id }}
                |mkdir -p /root/.rikkalaw
                |LOG=/root/.rikkalaw/bootstrap.log
                |echo "== bootstrap $(date) : ${selected.joinToString(" ") { it.id }}" >> "${'$'}LOG"
                |export DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_ROOT_USER_ACTION=ignore
                |if command -v apt-get >/dev/null 2>&1; then PM=apt
                |elif command -v apk >/dev/null 2>&1; then PM=apk
                |else echo "Unsupported rootfs: need apt-get (Ubuntu/Debian) or apk (Alpine)"; exit 2; fi
                |FAILED=""
                |step() { echo ">> ${'$'}*"; }
                |if [ "${'$'}PM" = apt ]; then
                |  APT="apt-get -y -q -o APT::Sandbox::User=root -o Dpkg::Options::=--force-confnew"
                |  step "apt-get update"
                |  ${'$'}APT update >> "${'$'}LOG" 2>&1 || FAILED="${'$'}FAILED apt-update"
                |  for p in ${q(apt)}; do
                |    if dpkg -s "${'$'}p" >/dev/null 2>&1; then continue; fi
                |    step "apt install ${'$'}p"
                |    ${'$'}APT install --no-install-recommends "${'$'}p" >> "${'$'}LOG" 2>&1 || FAILED="${'$'}FAILED ${'$'}p"
                |  done
                |  PIPS="${q(pipApt)}"
                |else
                |  step "apk update"
                |  apk update >> "${'$'}LOG" 2>&1 || FAILED="${'$'}FAILED apk-update"
                |  for p in ${q(apk)}; do
                |    step "apk add ${'$'}p"
                |    apk add --no-cache "${'$'}p" >> "${'$'}LOG" 2>&1 || FAILED="${'$'}FAILED ${'$'}p"
                |  done
                |  PIPS="${q(pipApk)}"
                |fi
                |if [ -n "${'$'}PIPS" ]; then
                |  if command -v python3 >/dev/null 2>&1; then
                |    for p in ${'$'}PIPS; do
                |      step "pip install ${'$'}p"
                |      python3 -m pip install --no-cache-dir --break-system-packages "${'$'}p" >> "${'$'}LOG" 2>&1 \
                |        || python3 -m pip install --no-cache-dir "${'$'}p" >> "${'$'}LOG" 2>&1 \
                |        || FAILED="${'$'}FAILED pip:${'$'}p"
                |    done
                |  else FAILED="${'$'}FAILED python3-missing"; fi
                |fi
                |echo
                |echo "== installed tools"
                |for t in python3 pip3 curl wget git jq pandoc tesseract ocrmypdf pdftotext; do
                |  command -v "${'$'}t" >/dev/null 2>&1 && echo "  ok  ${'$'}t"
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
                |if [ -n "${'$'}FAILED" ]; then echo "== failed:${'$'}FAILED (see ${'$'}LOG)"; exit 1; fi
                |echo "== done (log: ${'$'}LOG)"
                |""".trimMargin()
        }
    }
}
