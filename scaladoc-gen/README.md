# scaladoc-gen

Fills the `TODO FILL IN` Scaladoc markers that
[`todo-writer`](../todo-writer/README.md) leaves in the geny sources, by
pointing `opencode run` at one file at a time.

It is the module1 generator with a loop bolted on. `roman-gen` has nothing to
iterate over, so it runs one nested agent against an empty output directory and
waits. This one has five marked files and then some, so it:

1. scans a source tree for `.scala` files containing `TODO FILL IN`,
2. hands each one to a nested agent, run in *that file's directory* so it
   cannot wander into a sibling,
3. rescans, because a fully documented file no longer contains markers and
   drops out of the next round on its own.

`--passes 2` gives agents a second bite at files where the first one died
halfway, which is the common case: an agent that times out mid-file leaves
that file partially documented and it is still in the list next pass.

## Running

```sh
sbt "runMain Generate --dry"                     # print the plan, spawn nothing
sbt "runMain Generate"                           # fill every marked file
sbt "runMain Generate --file Writable.scala"     # just one
sbt "runMain Generate --passes 2 --verify"       # mop up, then `sbt test` in ../geny
sbt "runMain Generate --help"
```

Bare words are source directories, so `sbt "runMain Generate ../geny/geny/src"`
is the same as `--src ../geny/geny/src`.

## The prompt

`prompt.txt` next to this file is the prompt, and `{{FILE}}` in it is replaced
with the name of the file being documented. Edit it freely. Without a
`prompt.txt` a short built-in default is used instead.

Each file is one nested `opencode run --standalone --model
opencode/space-bunny-free --auto`. Override the model with
`SCALADOC_GEN_MODEL`.

`--standalone` is not optional and not a stylistic choice. Without it,
`opencode run` talks to the long-lived background service, and a stale or
wedged service answers *every* request — including a two-word prompt — with a
bare `UnexpectedStatus: 500`. It looks like a model or credentials problem; it
is neither. A private per-run server cannot be stale.

## Preflight

Before the first file is edited, one trivial nested run checks the agent works
at all. If it does not, you get one line naming the model id and the exact
command to try by hand, and **nothing is written**:

```
preflight: agent exited 1, so no file was touched.
  Check the model id, credentials, and that opencode works by hand:
    opencode run --standalone --model opencode/bad-model --auto 'hi'
aborting before any edits; nothing was written
```

Skip it with `--no-preflight`.

## Checking it without spawning anything

```sh
sbt "runMain ScanCheck"
```

`ScanCheck` exercises `parseArgs` and the scan against the real geny tree, and
asserts that `--dry` leaves the marker counts untouched. No agent is spawned.
The parser had two real bugs when this was written — `--dry ../dir` swallowed
the directory as a flag value, and the marker counter reported 1 per file
instead of 181 — both of which the checks now pin down.

## Notes

- **Edits in place.** There is no output directory. `geny` is its own git repo,
  so `git -C ../geny diff` shows exactly what an agent wrote and
  `git -C ../geny checkout -- .` reverts it. Worth a `git status` before a run.
- **A nested agent cannot verify its own claims.** It cannot write scratch
  files outside the geny tree, so when it documents edge-case behaviour it is
  reasoning from the standard library's contract, not from an executed test.
  Treat specific claims like "throws NPE here" as worth checking.
- **Idempotent.** A second run over a filled tree finds no markers and does
  nothing.
- **`target` and dot directories are skipped**, so compiled copies are never
  documented.
- **`opencode` inherits the ambient sandbox.** If sbt reports a permission
  error, run outside the sandbox or allow that path.