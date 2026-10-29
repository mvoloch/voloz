"""
Writing style per target app — the app that receives a dictation decides
how refinement formats it (an email gets paragraphs, a chat message stays
casual, an editor keeps identifiers untouched).

The desktop app sends the focused app's identity with each capture: the
lowercased executable name on Windows (``outlook.exe``) or the bundle id on
macOS (``com.microsoft.outlook``), plus the window title. A browser is
classified by its title, since Gmail and WhatsApp Web share ``chrome.exe``.
An app that is not in the table gets no style, and refinement behaves as
it always has.
"""

import re
from typing import Optional

EMAIL = "email"
CHAT = "chat"
CODE = "code"

# style → executable names (Windows) and bundle ids (macOS), lowercase.
APP_STYLES: dict[str, frozenset[str]] = {
    EMAIL: frozenset({
        "outlook.exe", "olk.exe", "thunderbird.exe",
        "com.microsoft.outlook", "com.apple.mail", "org.mozilla.thunderbird",
    }),
    CHAT: frozenset({
        "whatsapp.exe", "whatsapp.root.exe", "slack.exe", "ms-teams.exe", "teams.exe",
        "telegram.exe", "discord.exe", "signal.exe",
        "net.whatsapp.whatsapp", "com.tinyspeck.slackmacgap", "com.microsoft.teams2",
        "ru.keepcoder.telegram", "org.telegram.desktop", "com.hnc.discord",
    }),
    CODE: frozenset({
        "code.exe", "code - insiders.exe", "cursor.exe", "windsurf.exe", "zed.exe",
        "sublime_text.exe", "devenv.exe", "idea64.exe", "pycharm64.exe", "webstorm64.exe",
        "rider64.exe", "goland64.exe", "clion64.exe", "phpstorm64.exe", "rustrover64.exe",
        "datagrip64.exe", "windowsterminal.exe", "wt.exe", "powershell.exe", "pwsh.exe",
        "cmd.exe", "conhost.exe", "wezterm-gui.exe", "alacritty.exe",
        "com.microsoft.vscode", "com.todesktop.230313mzl4w4u92", "com.apple.terminal",
        "com.googlecode.iterm2", "dev.zed.zed",
    }),
}

# JetBrains IDEs on macOS all share this bundle-id prefix.
_CODE_BUNDLE_PREFIXES = ("com.jetbrains.",)

BROWSERS = frozenset({
    "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe",
    "arc.exe", "com.google.chrome", "com.microsoft.edgemac", "org.mozilla.firefox",
    "com.apple.safari", "com.brave.browser", "company.thebrowser.browser",
})

# Web apps recognised by a whole word in the browser tab title.
_BROWSER_TITLES: dict[str, re.Pattern] = {
    EMAIL: re.compile(r"\b(gmail|outlook)\b", re.IGNORECASE),
    CHAT: re.compile(r"\b(whatsapp|slack|microsoft teams|telegram|discord)\b", re.IGNORECASE),
}


def style_for_app(app: Optional[str], title: Optional[str] = None) -> Optional[str]:
    """Return ``"email"``, ``"chat"``, ``"code"`` or ``None`` for an unknown app."""
    if not app:
        return None
    key = app.strip().lower()
    for style, apps in APP_STYLES.items():
        if key in apps:
            return style
    if key.startswith(_CODE_BUNDLE_PREFIXES):
        return CODE
    if key in BROWSERS and title:
        for style, pattern in _BROWSER_TITLES.items():
            if pattern.search(title):
                return style
    return None


_STYLE_INSTRUCTIONS: dict[str, str] = {
    EMAIL: """The text goes into an email. Write complete sentences with proper capitalization and punctuation. When the dictation is long and moves between topics, split it into short paragraphs separated by a blank line. Do not add a greeting, sign-off or subject line the speaker did not say.""",
    CHAT: """The text goes into a chat message. Keep it short and casual, the way the speaker said it: keep their colloquial words, slang and contractions, and do not make it formal. When the message is a single short sentence, leave out the final period, even where other rules add one (a question mark or exclamation mark stays).""",
    CODE: """The text goes into a code editor or terminal. Keep every identifier, file name, command, flag and path exactly as dictated, including its casing. Do not restyle the text as prose and do not add a final period after a command, identifier or path.""",
}


def style_instruction(style: Optional[str]) -> Optional[str]:
    """The refinement prompt section for ``style``, or ``None``."""
    return _STYLE_INSTRUCTIONS.get(style) if style else None


# A chat message this short with no sentence break inside is "a single short
# sentence". Measured on Claude Haiku and Qwen 3.8 27B (2026-10-29): both kept
# the final period ("Beleza, valeu.") despite the prompt line — the few-shot
# examples all end in one — so it is dropped here.
_SHORT_CHAT_MAX_CHARS = 120
_SENTENCE_BREAK_RE = re.compile(r"[.!?…]\s")


def finish_for_style(text: str, style: Optional[str]) -> str:
    """Apply the parts of a style that a model doesn't follow reliably."""
    if style != CHAT:
        return text
    if (
        text.endswith(".")
        and not text.endswith("..")
        and len(text) <= _SHORT_CHAT_MAX_CHARS
        and "\n" not in text
        and not _SENTENCE_BREAK_RE.search(text[:-1])
    ):
        return text[:-1]
    return text
