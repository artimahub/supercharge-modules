import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** Drives `opencode run` headlessly to generate a Roman numeral sbt project.
  *
  * Usage:
  *   sbt "runMain Generate"                       # -> ../roman-generated
  *   sbt "runMain Generate ../some/dir"           # custom output directory
  *   sbt "runMain Generate ../some/dir verify"    # also run `sbt test` afterwards
  *   sbt "runMain Generate verify"                # -> ../roman-generated, then verify
  *
  * `verify` is a flag wherever it appears; the only positional argument is the
  * output directory.
  *
  * The prompt is read from prompt.txt next to this file if present, otherwise
  * a built-in default is used. Override the model with ROMAN_GEN_MODEL.
  */
object Generate:
  private val defaultModel = "opencode/space-bunny-free"
  private val defaultTarget = "../roman-generated"

  private val builtinPrompt =
    """Build a Scala 3 sbt project in the current directory defining an
      |`object Roman` with toRoman(n: Int): String and
      |fromRoman(s: String): Option[Int], plus MUnit tests including a
      |round-trip check over 1..3999. Run `sbt test` until it passes.""".stripMargin

  def main(args: Array[String]): Unit =
    val (positionals, flags) = parseArgs(args.toList)
    if positionals.size > 1 then
      sys.error(
        s"expected at most one output directory, got: ${positionals.mkString(" ")}\n" +
          "usage: sbt \"runMain Generate [outputDir] [verify]\""
      )
    val target = Path
      .of(positionals.headOption.getOrElse(defaultTarget))
      .toAbsolutePath
      .normalize()
    // "verify" is a flag, never a directory name. Previously it was taken as
    // args.head, so `runMain Generate verify` created a directory named
    // "verify" and generated the project into it.
    val verify = flags.contains("verify")
    val model = sys.env.getOrElse("ROMAN_GEN_MODEL", defaultModel)

    Files.createDirectories(target)
    println(s"model : $model")
    println(s"target: $target")
    println(s"verify: $verify")
    println("-" * 60)

    val exitCode = run(buildCommand(model, prompt(target), target), target)
    println("-" * 60)

    if exitCode != 0 then
      println(s"opencode exited $exitCode")
      sys.exit(exitCode)

    val result = target.resolve("build.sbt")
    if !Files.exists(result) then
      println(s"warning: no build.sbt found in $target — the model may not have written files")
    else
      println(s"generated project at $target")

    if verify then
      println("-" * 60)
      println("verifying with `sbt test`...")
      val code = run(shellIn(target, "sbt", "-batch", "test"), target)
      println(s"sbt test exited $code")
      sys.exit(code)

  /** Splits args into positionals and flags. `verify` (with or without `--`) is
    * always a flag; everything else positional.
    *
    * Not private so ArgCheck can exercise it directly.
    */
  def parseArgs(args: List[String]): (List[String], Set[String]) =
    // partition puts the matching elements first, so the flag list is first.
    args.partition(a => a == "verify" || a == "--verify" || a.startsWith("--")) match
      case (flags, positionals) =>
        (positionals, flags.map(_.stripPrefix("--")).toSet)

  private val timeoutMinutes = 30L

  /** Spawns `cmd` in `cwd` and waits for it.
    *
    * stdin is wired to /dev/null on purpose: `ProcessBuilder` otherwise hands the
    * child a pipe that nobody writes to or closes, and `opencode run` blocks
    * forever reading it before it ever reaches the server. stdout/stderr are
    * inherited so the nested agent's output stays visible.
    */
  private def run(cmd: Seq[String], cwd: Path): Int =
    val pb = ProcessBuilder(cmd*)
    pb.directory(cwd.toFile)
    pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    val proc = pb.start()
    if !proc.waitFor(timeoutMinutes, TimeUnit.MINUTES) then
      proc.destroyForcibly()
      println(s"timed out after $timeoutMinutes minutes: ${cmd.mkString(" ")}")
      124
    else proc.exitValue()

  /** Runs through `zsh -c` with an explicit `cd`: `Process(cmd, dir)` has been
    * observed to leave the child in the sbt launch directory instead of the
    * requested one, which silently pointed the nested agent at the wrong tree.
    */
  private def shellIn(dir: Path, program: String, args: String*): Seq[String] =
    val cmd = (program +: args).map(shellQuote).mkString(" ")
    Seq("/bin/zsh", "-c", s"cd ${shellQuote(dir.toString)} && exec $cmd")

  private def buildCommand(model: String, prompt: String, target: Path): Seq[String] =
    shellIn(
      target,
      "opencode", "run",
      "--model", model,
      "--title", "roman-gen",
      "--auto", // headless run cannot answer interactive permission prompts
      prompt
    )

  private def shellQuote(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"

  /** prompt.txt next to the build, else the one in the sbt launch directory,
    * else the built-in default.
    */
  private def prompt(target: Path): String =
    List(
      target.resolve("prompt.txt"),
      Path.of("prompt.txt"),
      Path.of("roman-gen/prompt.txt")
    )
      .find(Files.isRegularFile(_))
      .map(Files.readString)
      .getOrElse(builtinPrompt)
