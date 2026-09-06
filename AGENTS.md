# Celeste module changes

For new or changed modules, use the `celeste-module-qa` skill at `C:/Users/c4lam/.codex/skills/celeste-module-qa/SKILL.md` when available. Its runner is `tools/qa/module_qa.py` (`--help`).

Run focused regression checks and an incremental build, then test enable/trigger, disable/reset and a relevant edge case in an authorized local QA world. Capture and inspect a fresh in-game screenshot of the tested build. If game access/build identity is unavailable, explicitly report partial verification; a GPU preview is not a game screenshot.

The user chose the normal Celeste launcher route, then Modrinth's first Minecraft 1.21.11 option, for in-game tests. Reuse that choice; preserve authentication and other instances.

Keep detailed logs under the ignored `build/module-qa/` directory. Handoff: short result, one useful picture, and any necessary blocker. Avoid whole-repository rereads and repeated full builds. QA does not authorize publishing, auth changes or overwriting an existing game instance.
