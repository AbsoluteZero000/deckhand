#!/usr/bin/env python3
"""deckhand: flashcard content tool for humans and agents.

A cards repo keeps cards in cards/<deck>/<section>.yaml. This tool adds, edits,
removes, validates, formats and builds them into dist/deck.json +
dist/manifest.json, which the Android app downloads.

It works on the cards repo you run it in (the nearest folder at or above the
current one that has a cards/ folder), or the one given by --root or the
DECKHAND_ROOT environment variable.

    deckhand init my-cards        # start a new cards repo
    deckhand --help
    deckhand <command> --help
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
from importlib import resources
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is missing. Run: pip install pyyaml")

ROOT = Path.cwd()
CARDS_DIR = ROOT / "cards"
SCHEMA_VERSION = 1
DECKHAND_REPO = "AbsoluteZero000/deckhand"

ID_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,78}[a-z0-9])?$")
TAG_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
DECK_KEYS = ["id", "name", "description", "order"]
SECTION_KEYS = ["id", "name", "order"]
CARD_KEYS = ["id", "front", "back", "why", "tags"]


# ---------------------------------------------------------------- yaml style
class _Dumper(yaml.SafeDumper):
    pass


def _repr_str(dumper, s):
    return dumper.represent_scalar("tag:yaml.org,2002:str", s, style="|" if "\n" in s else None)


def _repr_list(dumper, items):
    flow = all(isinstance(x, str) for x in items)
    return dumper.represent_sequence("tag:yaml.org,2002:seq", items, flow_style=flow)


_Dumper.add_representer(str, _repr_str)
_Dumper.add_representer(list, _repr_list)


def dump_yaml(obj) -> str:
    return yaml.dump(obj, Dumper=_Dumper, sort_keys=False, allow_unicode=True,
                     width=10_000, default_flow_style=False, indent=2)


# ---------------------------------------------------------------- model
class Fail(Exception):
    pass


def rel(p: Path) -> str:
    try:
        return p.relative_to(ROOT).as_posix()
    except ValueError:
        return str(p)


def slugify(text: str, limit: int = 60) -> str:
    s = re.sub(r"`", "", text.lower())
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    s = s[:limit].rstrip("-")
    return s or "card"


def canonical_card(c: dict) -> dict:
    out = {"id": c["id"], "front": c["front"], "back": c["back"]}
    if c.get("why"):
        out["why"] = c["why"]
    if c.get("tags"):
        out["tags"] = list(c["tags"])
    return out


class Repo:
    """Loads every deck and section file. Collects problems instead of stopping."""

    def __init__(self, root: Path | None = None):
        self.root = root or CARDS_DIR
        self.decks: dict[str, dict] = {}       # deck id -> {meta, path, sections: {sid: section}}
        self.errors: list[str] = []
        self._load()

    # -- loading
    def _read(self, path: Path):
        try:
            return yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as e:
            self.errors.append(f"{rel(path)}: not valid YAML ({e})")
            return None

    def _load(self):
        if not self.root.exists():
            return
        for ddir in sorted(p for p in self.root.iterdir() if p.is_dir()):
            dpath = ddir / "deck.yaml"
            if not dpath.exists():
                self.errors.append(f"{rel(ddir)}: missing deck.yaml")
                continue
            meta = self._read(dpath) or {}
            if not isinstance(meta, dict):
                self.errors.append(f"{rel(dpath)}: must be a mapping")
                continue
            deck = {"meta": meta, "path": dpath, "sections": {}}
            self.decks[ddir.name] = deck
            for spath in sorted(ddir.glob("*.yaml")):
                if spath.name == "deck.yaml":
                    continue
                data = self._read(spath)
                if data is None:
                    continue
                if not isinstance(data, dict):
                    self.errors.append(f"{rel(spath)}: must be a mapping with 'section' and 'cards'")
                    continue
                sec = data.get("section") or {}
                cards = data.get("cards") or []
                deck["sections"][spath.stem] = {"meta": sec, "path": spath, "cards": cards, "extra": [k for k in data if k not in ("section", "cards")]}

    # -- queries
    def all_cards(self):
        for did, deck in self.decks.items():
            for sid, sec in sorted(deck["sections"].items(), key=lambda kv: (kv[1]["meta"].get("order", 1e9) if isinstance(kv[1]["meta"], dict) else 1e9, kv[0])):
                for i, c in enumerate(sec["cards"]):
                    if isinstance(c, dict):
                        yield did, sid, i, c

    def find(self, card_id: str):
        for did, sid, i, c in self.all_cards():
            if c.get("id") == card_id:
                return did, sid, i, c
        raise Fail(f"No card with id '{card_id}'")

    def ids(self) -> set[str]:
        return {c.get("id") for *_, c in self.all_cards()}

    def section(self, deck_id: str, section_id: str) -> dict:
        if deck_id not in self.decks:
            raise Fail(f"No deck '{deck_id}'. Decks: {', '.join(self.decks) or 'none'}. Create one with new-deck.")
        secs = self.decks[deck_id]["sections"]
        if section_id not in secs:
            raise Fail(f"No section '{section_id}' in deck '{deck_id}'. Sections: {', '.join(secs) or 'none'}. Create one with new-section.")
        return secs[section_id]

    def unique_id(self, base: str) -> str:
        taken = self.ids()
        cand, n = base, 2
        while cand in taken:
            suffix = f"-{n}"
            cand = base[: 80 - len(suffix)].rstrip("-") + suffix
            n += 1
        return cand

    # -- saving
    def save_section(self, sec: dict):
        body = {"section": {k: sec["meta"][k] for k in SECTION_KEYS if k in sec["meta"]},
                "cards": [canonical_card(c) for c in sec["cards"]]}
        sec["path"].write_text(dump_yaml(body), encoding="utf-8", newline="\n")

    def save_deck(self, deck: dict):
        meta = {k: deck["meta"][k] for k in DECK_KEYS if k in deck["meta"]}
        deck["path"].write_text(dump_yaml(meta), encoding="utf-8", newline="\n")

    # -- validation
    def validate(self) -> list[str]:
        errs = list(self.errors)
        seen: dict[str, str] = {}
        for did, deck in self.decks.items():
            m, p = deck["meta"], rel(deck["path"])
            for k in m:
                if k not in DECK_KEYS:
                    errs.append(f"{p}: unknown key '{k}' (allowed: {', '.join(DECK_KEYS)})")
            if m.get("id") != did:
                errs.append(f"{p}: id must be '{did}' (the folder name)")
            if not isinstance(m.get("name"), str) or not m.get("name", "").strip():
                errs.append(f"{p}: 'name' is required")
            if "order" in m and not isinstance(m["order"], int):
                errs.append(f"{p}: 'order' must be a whole number")
            if not ID_RE.match(did):
                errs.append(f"{p}: deck folder name must be lowercase letters, digits and dashes")
            for sid, sec in deck["sections"].items():
                sp, sm = rel(sec["path"]), sec["meta"]
                for k in sec["extra"]:
                    errs.append(f"{sp}: unknown top-level key '{k}' (allowed: section, cards)")
                if not isinstance(sm, dict):
                    errs.append(f"{sp}: 'section' must be a mapping")
                    continue
                for k in sm:
                    if k not in SECTION_KEYS:
                        errs.append(f"{sp}: unknown section key '{k}' (allowed: {', '.join(SECTION_KEYS)})")
                if sm.get("id") != sid:
                    errs.append(f"{sp}: section.id must be '{sid}' (the file name)")
                if not isinstance(sm.get("name"), str) or not sm.get("name", "").strip():
                    errs.append(f"{sp}: section.name is required")
                if "order" in sm and not isinstance(sm["order"], int):
                    errs.append(f"{sp}: section.order must be a whole number")
                if not isinstance(sec["cards"], list):
                    errs.append(f"{sp}: 'cards' must be a list")
                    continue
                for i, c in enumerate(sec["cards"]):
                    where = f"{sp} card #{i + 1}"
                    if not isinstance(c, dict):
                        errs.append(f"{where}: must be a mapping")
                        continue
                    cid = c.get("id")
                    if cid:
                        where = f"{sp} [{cid}]"
                    for k in c:
                        if k not in CARD_KEYS:
                            errs.append(f"{where}: unknown key '{k}' (allowed: {', '.join(CARD_KEYS)})")
                    if not isinstance(cid, str) or not ID_RE.match(cid):
                        errs.append(f"{where}: 'id' must be 2-80 chars of lowercase letters, digits and dashes")
                    elif cid in seen:
                        errs.append(f"{where}: duplicate id, also in {seen[cid]}")
                    else:
                        seen[cid] = sp
                    for k in ("front", "back"):
                        if not isinstance(c.get(k), str) or not c.get(k, "").strip():
                            errs.append(f"{where}: '{k}' is required text")
                    if "why" in c and c["why"] is not None and not isinstance(c["why"], str):
                        errs.append(f"{where}: 'why' must be text")
                    tags = c.get("tags", [])
                    if tags is None:
                        tags = []
                    if not isinstance(tags, list):
                        errs.append(f"{where}: 'tags' must be a list like [routing, nat]")
                    else:
                        for t in tags:
                            if not isinstance(t, str) or not TAG_RE.match(t):
                                errs.append(f"{where}: bad tag {t!r} (lowercase letters, digits, dashes; max 40)")
                        if len(set(tags)) != len(tags):
                            errs.append(f"{where}: repeated tag")
        return errs

    def require_valid(self):
        errs = self.validate()
        if errs:
            raise Fail("Content has problems; fix them first:\n  " + "\n  ".join(errs))

    # -- build
    def build(self) -> dict:
        decks, cards = [], []
        for did, deck in sorted(self.decks.items(), key=lambda kv: (kv[1]["meta"].get("order", 1e9), kv[0])):
            secs = sorted(deck["sections"].items(), key=lambda kv: (kv[1]["meta"].get("order", 1e9), kv[0]))
            decks.append({
                "id": did, "name": deck["meta"]["name"], "description": deck["meta"].get("description", ""),
                "order": deck["meta"].get("order", 0),
                "sections": [{"id": sid, "name": s["meta"]["name"], "order": s["meta"].get("order", 0)} for sid, s in secs],
            })
            n = 0
            for sid, s in secs:
                for c in s["cards"]:
                    cards.append({"id": c["id"], "deck": did, "section": sid, "front": c["front"], "back": c["back"],
                                  "why": c.get("why") or "", "tags": c.get("tags") or [], "order": n})
                    n += 1
        content = {"decks": decks, "cards": cards}
        version = hashlib.sha256(json.dumps(content, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:12]
        return {"schema": SCHEMA_VERSION, "version": version, **content}


# ---------------------------------------------------------------- commands
def split_tags(s: str | None) -> list[str]:
    if not s:
        return []
    return [t.strip().lower() for t in s.split(",") if t.strip()]


def card_line(did, sid, c) -> str:
    tags = ",".join(c.get("tags") or [])
    return f"{c['id']}  [{did}/{sid}]{'  #' + tags if tags else ''}\n    {c['front']}"


def cmd_validate(a):
    r = Repo()
    errs = r.validate()
    n = sum(1 for _ in r.all_cards())
    if errs:
        print("\n".join(errs))
        print(f"\n{len(errs)} problem(s) in {n} cards.")
        return 1
    print(f"OK: {len(r.decks)} deck(s), {sum(len(d['sections']) for d in r.decks.values())} section(s), {n} cards.")
    return 0


def cmd_fmt(a):
    r = Repo()
    r.require_valid()
    changed = []
    for deck in r.decks.values():
        files = [(deck["path"], dump_yaml({k: deck["meta"][k] for k in DECK_KEYS if k in deck["meta"]}))]
        for sec in deck["sections"].values():
            body = {"section": {k: sec["meta"][k] for k in SECTION_KEYS if k in sec["meta"]},
                    "cards": [canonical_card(c) for c in sec["cards"]]}
            files.append((sec["path"], dump_yaml(body)))
        for path, text in files:
            if path.read_text(encoding="utf-8") != text:
                changed.append(rel(path))
                if not a.check:
                    path.write_text(text, encoding="utf-8", newline="\n")
    if a.check:
        if changed:
            print("Not formatted (run: deckhand fmt):\n  " + "\n  ".join(changed))
            return 1
        print("All files formatted.")
        return 0
    print(f"Formatted {len(changed)} file(s)." if changed else "Nothing to format.")
    return 0


def cmd_list(a):
    r = Repo()
    want = set(split_tags(",".join(a.tag or [])))
    q = (a.search or "").lower()
    rows = []
    for did, sid, _, c in r.all_cards():
        if a.deck and did != a.deck:
            continue
        if a.section and sid != a.section:
            continue
        if want and not want.issubset(set(c.get("tags") or [])):
            continue
        if q and q not in " ".join(str(c.get(k, "")) for k in ("id", "front", "back", "why")).lower():
            continue
        rows.append((did, sid, c))
    if a.json:
        print(json.dumps([{"deck": d, "section": s, **canonical_card(c)} for d, s, c in rows], ensure_ascii=False, indent=2))
    else:
        for d, s, c in rows:
            print(card_line(d, s, c))
        print(f"\n{len(rows)} card(s).")
    return 0


def cmd_show(a):
    r = Repo()
    out = []
    for cid in a.ids:
        did, sid, _, c = r.find(cid)
        out.append({"deck": did, "section": sid, **canonical_card(c)})
    print(dump_yaml(out if len(out) > 1 else out[0]))
    return 0


def _add_one(r: Repo, deck: str, section: str, front: str, back: str, why: str | None, tags, cid: str | None) -> str:
    sec = r.section(deck, section)
    if cid:
        if not ID_RE.match(cid):
            raise Fail(f"Bad id '{cid}': use lowercase letters, digits and dashes (2-80 chars)")
        if cid in r.ids():
            raise Fail(f"Id '{cid}' already exists. Ids are never reused; pick another or omit --id.")
    else:
        cid = r.unique_id(f"{deck}-{slugify(front)}")
    card = {"id": cid, "front": front.strip(), "back": back.strip()}
    if why and why.strip():
        card["why"] = why.strip()
    if tags:
        card["tags"] = list(dict.fromkeys(tags))
    sec["cards"].append(card)
    return cid


def cmd_add(a):
    r = Repo()
    r.require_valid()
    cid = _add_one(r, a.deck, a.section, a.front, a.back, a.why, split_tags(a.tags), a.id)
    r.require_valid()
    r.save_section(r.section(a.deck, a.section))
    print(f"Added {cid}")
    return 0


def cmd_import(a):
    path = Path(a.file)
    raw = path.read_text(encoding="utf-8")
    items = json.loads(raw) if path.suffix.lower() == ".json" else yaml.safe_load(raw)
    if not isinstance(items, list):
        raise Fail("Import file must be a list of cards, each with deck, section, front, back (why, tags, id optional)")
    r = Repo()
    r.require_valid()
    touched, added = {}, []
    for i, it in enumerate(items, 1):
        if not isinstance(it, dict):
            raise Fail(f"Item {i} is not a mapping")
        missing = [k for k in ("deck", "section", "front", "back") if not it.get(k)]
        if missing:
            raise Fail(f"Item {i} is missing: {', '.join(missing)}")
        tags = it.get("tags") or []
        if isinstance(tags, str):
            tags = split_tags(tags)
        cid = _add_one(r, it["deck"], it["section"], it["front"], it["back"], it.get("why"), [t.lower() for t in tags], it.get("id"))
        added.append(cid)
        touched[(it["deck"], it["section"])] = True
    r.require_valid()
    if a.dry_run:
        print(f"Would add {len(added)} card(s):\n  " + "\n  ".join(added))
        return 0
    for d, s in touched:
        r.save_section(r.section(d, s))
    print(f"Added {len(added)} card(s):\n  " + "\n  ".join(added))
    return 0


def cmd_edit(a):
    r = Repo()
    r.require_valid()
    did, sid, idx, c = r.find(a.id)
    if a.front is not None:
        c["front"] = a.front.strip()
    if a.back is not None:
        c["back"] = a.back.strip()
    if a.why is not None:
        c["why"] = a.why.strip()
    tags = list(c.get("tags") or [])
    if a.tags is not None:
        tags = split_tags(a.tags)
    for t in split_tags(",".join(a.add_tag or [])):
        if t not in tags:
            tags.append(t)
    rm = set(split_tags(",".join(a.remove_tag or [])))
    tags = [t for t in tags if t not in rm]
    c["tags"] = tags
    src = r.section(did, sid)
    if a.move_to:
        dst_deck, _, dst_sec = a.move_to.partition("/")
        if not dst_sec:
            dst_deck, dst_sec = did, dst_deck
        dst = r.section(dst_deck, dst_sec)
        if dst is not src:
            src["cards"].pop(idx)
            dst["cards"].append(c)
            r.require_valid()
            r.save_section(src)
            r.save_section(dst)
            print(f"Edited {a.id} (moved to {dst_deck}/{dst_sec})")
            return 0
    r.require_valid()
    r.save_section(src)
    print(f"Edited {a.id}")
    return 0


def cmd_rm(a):
    r = Repo()
    r.require_valid()
    touched = []
    for cid in a.ids:
        did, sid, idx, _ = r.find(cid)
        sec = r.section(did, sid)
        sec["cards"].pop(idx)
        if sec not in touched:
            touched.append(sec)
    for sec in touched:
        r.save_section(sec)
    print(f"Removed {len(a.ids)} card(s): {', '.join(a.ids)}")
    return 0


def cmd_tags(a):
    r = Repo()
    counts: dict[str, int] = {}
    for did, _, _, c in r.all_cards():
        if a.deck and did != a.deck:
            continue
        for t in c.get("tags") or []:
            counts[t] = counts.get(t, 0) + 1
    for t, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"{n:5d}  {t}")
    return 0


def cmd_tree(a):
    r = Repo()
    for did, deck in r.decks.items():
        secs = deck["sections"]
        total = sum(len(s["cards"]) for s in secs.values())
        print(f"{did}  ({deck['meta'].get('name', '?')}, {total} cards)")
        for sid, s in sorted(secs.items(), key=lambda kv: (kv[1]["meta"].get("order", 1e9), kv[0])):
            print(f"  {sid:32s} {len(s['cards']):4d}  {s['meta'].get('name', '')}")
    return 0


def cmd_new_deck(a):
    r = Repo()
    if not ID_RE.match(a.id):
        raise Fail("Deck id must be lowercase letters, digits and dashes")
    if a.id in r.decks:
        raise Fail(f"Deck '{a.id}' already exists")
    ddir = CARDS_DIR / a.id
    ddir.mkdir(parents=True, exist_ok=False)
    meta = {"id": a.id, "name": a.name}
    if a.description:
        meta["description"] = a.description
    meta["order"] = a.order if a.order is not None else len(r.decks) + 1
    (ddir / "deck.yaml").write_text(dump_yaml(meta), encoding="utf-8", newline="\n")
    print(f"Created deck {a.id} at {rel(ddir)}")
    return 0


def cmd_new_section(a):
    r = Repo()
    if a.deck not in r.decks:
        raise Fail(f"No deck '{a.deck}'")
    if not ID_RE.match(a.id):
        raise Fail("Section id must be lowercase letters, digits and dashes")
    if a.id in r.decks[a.deck]["sections"]:
        raise Fail(f"Section '{a.id}' already exists in {a.deck}")
    path = CARDS_DIR / a.deck / f"{a.id}.yaml"
    order = a.order if a.order is not None else len(r.decks[a.deck]["sections"]) + 1
    path.write_text(dump_yaml({"section": {"id": a.id, "name": a.name, "order": order}, "cards": []}), encoding="utf-8", newline="\n")
    print(f"Created section {a.deck}/{a.id} at {rel(path)}")
    return 0


def cmd_build(a):
    r = Repo()
    r.require_valid()
    data = r.build()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    data["generated_at"] = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    deck_bytes = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    (out / "deck.json").write_bytes(deck_bytes)
    manifest = {
        "schema": SCHEMA_VERSION, "version": data["version"], "generated_at": data["generated_at"],
        "deck_url": "deck.json", "sha256": hashlib.sha256(deck_bytes).hexdigest(),
        "decks": len(data["decks"]), "cards": len(data["cards"]),
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8", newline="\n")
    print(f"Built version {data['version']}: {len(data['cards'])} cards in {len(data['decks'])} deck(s) -> {rel(out)}")
    return 0


def _template_files():
    """Yield (relative path in the new repo, text) for the starter cards repo.

    Template files are stored with a 'dot-' prefix instead of a leading dot so
    packaging tools keep them.
    """
    base = resources.files("deckhand") / "template"

    def walk(node, prefix=""):
        for child in sorted(node.iterdir(), key=lambda c: c.name):
            name = "." + child.name[4:] if child.name.startswith("dot-") else child.name
            if child.is_dir():
                yield from walk(child, f"{prefix}{name}/")
            else:
                yield f"{prefix}{name}", child.read_text(encoding="utf-8")

    yield from walk(base)


def cmd_init(a):
    target = Path(a.dir).resolve()
    if target.exists() and any(target.iterdir()):
        raise Fail(f"{target} is not empty. Pick a new folder.")
    values = {"{{NAME}}": a.name or target.name, "{{DECKHAND_REPO}}": a.deckhand_repo,
              "{{DECKHAND_REF}}": a.deckhand_ref}
    written = []
    for relpath, text in _template_files():
        for k, v in values.items():
            text = text.replace(k, v)
        dest = target / relpath
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text, encoding="utf-8", newline="\n")
        written.append(relpath)
    print(f"Created a cards repo in {target}:\n  " + "\n  ".join(written))
    print("\nNext: put it on GitHub as a PRIVATE repo, e.g.\n"
          f"  cd {target}\n  git init -b main; git add -A; git commit -m \"Start cards\"\n"
          f"  gh repo create {values['{{NAME}}']} --private --source . --push\n"
          "Then point the app at that repo (see README.md there).")
    return 0


def find_root(explicit: str | None) -> Path:
    if explicit or os.environ.get("DECKHAND_ROOT"):
        return Path(explicit or os.environ["DECKHAND_ROOT"]).resolve()
    here = Path.cwd().resolve()
    for d in (here, *here.parents):
        if (d / "cards").is_dir():
            return d
    raise Fail("No cards/ folder here or in any parent folder. Run this inside a cards repo, "
               "pass --root <path>, or start one with: deckhand init <folder>")


def main(argv=None):
    global ROOT, CARDS_DIR
    p = argparse.ArgumentParser(prog="deckhand", description="Edit, check and build flashcard content.")
    p.add_argument("--root", help="cards repo to work on (default: nearest folder with cards/, or $DECKHAND_ROOT)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("init", help="create a new cards repo from the starter template")
    s.add_argument("dir", help="new or empty folder")
    s.add_argument("--name", help="repo name used in the docs (default: folder name)")
    s.add_argument("--deckhand-repo", default=DECKHAND_REPO, help=f"where CI gets deckhand from (default {DECKHAND_REPO})")
    s.add_argument("--deckhand-ref", default="main", help="branch or tag of deckhand that CI uses (default main)")
    s.set_defaults(fn=cmd_init, needs_root=False)

    s = sub.add_parser("validate", help="check every file; exits 1 on problems")
    s.set_defaults(fn=cmd_validate)

    s = sub.add_parser("fmt", help="rewrite files in the canonical layout")
    s.add_argument("--check", action="store_true", help="only report files that would change")
    s.set_defaults(fn=cmd_fmt)

    s = sub.add_parser("tree", help="show decks and sections with card counts")
    s.set_defaults(fn=cmd_tree)

    s = sub.add_parser("list", help="list cards, optionally filtered")
    s.add_argument("--deck"); s.add_argument("--section")
    s.add_argument("--tag", action="append", help="repeatable or comma-separated; cards must have all")
    s.add_argument("--search", help="text found in id, front, back or why")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_list)

    s = sub.add_parser("show", help="print cards by id")
    s.add_argument("ids", nargs="+")
    s.set_defaults(fn=cmd_show)

    s = sub.add_parser("add", help="add one card")
    s.add_argument("--deck", required=True); s.add_argument("--section", required=True)
    s.add_argument("--front", required=True); s.add_argument("--back", required=True)
    s.add_argument("--why"); s.add_argument("--tags", help="comma-separated, e.g. routing,exam-trap")
    s.add_argument("--id", help="optional; generated from deck + front when omitted")
    s.set_defaults(fn=cmd_add)

    s = sub.add_parser("import", help="add many cards from a YAML or JSON list")
    s.add_argument("file"); s.add_argument("--dry-run", action="store_true")
    s.set_defaults(fn=cmd_import)

    s = sub.add_parser("edit", help="change one card")
    s.add_argument("id")
    s.add_argument("--front"); s.add_argument("--back"); s.add_argument("--why")
    s.add_argument("--tags", help="replace all tags (comma-separated)")
    s.add_argument("--add-tag", action="append"); s.add_argument("--remove-tag", action="append")
    s.add_argument("--move-to", help="section id, or deck/section")
    s.set_defaults(fn=cmd_edit)

    s = sub.add_parser("rm", help="remove cards by id")
    s.add_argument("ids", nargs="+")
    s.set_defaults(fn=cmd_rm)

    s = sub.add_parser("tags", help="list tags with counts")
    s.add_argument("--deck")
    s.set_defaults(fn=cmd_tags)

    s = sub.add_parser("new-deck", help="create a deck (top-level group)")
    s.add_argument("id"); s.add_argument("--name", required=True)
    s.add_argument("--description"); s.add_argument("--order", type=int)
    s.set_defaults(fn=cmd_new_deck)

    s = sub.add_parser("new-section", help="create a section inside a deck")
    s.add_argument("--deck", required=True); s.add_argument("--id", required=True)
    s.add_argument("--name", required=True); s.add_argument("--order", type=int)
    s.set_defaults(fn=cmd_new_section)

    s = sub.add_parser("build", help="write dist/deck.json and dist/manifest.json")
    s.add_argument("--out", help="output folder (default: dist/ in the cards repo)")
    s.set_defaults(fn=cmd_build)

    a = p.parse_args(argv)
    try:
        if getattr(a, "needs_root", True):
            ROOT = find_root(a.root)
            CARDS_DIR = ROOT / "cards"
            if not CARDS_DIR.is_dir():
                raise Fail(f"{ROOT} has no cards/ folder")
        if a.cmd == "build" and not a.out:
            a.out = str(ROOT / "dist")
        return a.fn(a)
    except Fail as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
